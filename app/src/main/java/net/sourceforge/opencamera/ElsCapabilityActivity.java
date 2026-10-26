package net.sourceforge.opencamera;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ELS phase 2: capability report.
 *  Lists what the phone's cameras, encoders and sensors expose to third-party apps, so we know
 *  whether 2K30 capture, sharpening-off and noise reduction control are possible on this device
 *  before building on them. The report can be copied or shared as plain text.
 */
public class ElsCapabilityActivity extends Activity {
    private static final String TAG = "ElsCapability";

    // the brief's capture target and fallback
    private static final Size SIZE_2K = new Size(2560, 1440);
    private static final Size SIZE_1080P = new Size(1920, 1080);
    private static final int TARGET_FPS = 30;

    private TextView text_view;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.els_capability_report);

        int padding = (int)(12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy_button = new Button(this);
        copy_button.setText(R.string.els_capability_copy);
        copy_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReport();
            }
        });
        Button share_button = new Button(this);
        share_button.setText(R.string.els_capability_share);
        share_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareReport();
            }
        });
        buttons.addView(copy_button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        buttons.addView(share_button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(buttons);

        ScrollView scroll_view = new ScrollView(this);
        text_view = new TextView(this);
        text_view.setTypeface(android.graphics.Typeface.MONOSPACE);
        text_view.setTextSize(11);
        text_view.setTextIsSelectable(true);
        text_view.setText(R.string.els_capability_collecting);
        scroll_view.addView(text_view);
        root.addView(scroll_view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);

        // MediaCodecList and camera queries can take a moment, so keep them off the UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = buildReport();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        report = result;
                        text_view.setText(result);
                    }
                });
            }
        }).start();
    }

    private void copyReport() {
        if( report.isEmpty() )
            return;
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.els_capability_report), report));
        Toast.makeText(this, R.string.els_capability_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if( report.isEmpty() )
            return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.els_capability_report));
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, getString(R.string.els_capability_share)));
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        try {
            appendDevice(sb);
            appendSensors(sb);
            appendCameras(sb);
            appendCamcorderProfiles(sb);
            appendEncoders(sb);
        }
        catch(RuntimeException e) {
            // never lose what we already collected
            Log.e(TAG, "failed building capability report", e);
            sb.append("\n!! report stopped early: ").append(e).append("\n");
        }
        String result = sb.toString();
        // also log it, so it can be read with logcat
        for(String line : result.split("\n")) {
            Log.i(TAG, line);
        }
        return result;
    }

    private static void heading(StringBuilder sb, String title) {
        sb.append("\n== ").append(title).append(" ==\n");
    }

    private void appendDevice(StringBuilder sb) {
        sb.append("ELS Camera capability report\n");
        heading(sb, "Device");
        sb.append("Model: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ) {
            sb.append("SoC: ").append(Build.SOC_MANUFACTURER).append(" ").append(Build.SOC_MODEL).append("\n");
        }
        else {
            sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
        }
        ActivityManager activity_manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory_info = new ActivityManager.MemoryInfo();
        activity_manager.getMemoryInfo(memory_info);
        sb.append("RAM: ").append(memory_info.totalMem / (1024 * 1024)).append(" MB, app heap limit: ")
                .append(activity_manager.getMemoryClass()).append(" MB\n");
        sb.append("CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
    }

    private void appendSensors(StringBuilder sb) {
        heading(sb, "Motion sensors");
        SensorManager sensor_manager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        int [] types = {Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD};
        String [] names = {"Gyroscope", "Gyroscope (uncalibrated)", "Accelerometer",
                "Gravity", "Linear acceleration", "Rotation vector",
                "Game rotation vector", "Magnetometer"};
        for(int i=0;i<types.length;i++) {
            Sensor sensor = sensor_manager.getDefaultSensor(types[i]);
            sb.append(names[i]).append(": ");
            if( sensor == null ) {
                sb.append("no\n");
            }
            else {
                sb.append("yes (").append(sensor.getName());
                if( sensor.getMinDelay() > 0 ) {
                    sb.append(", up to ").append(1000000 / sensor.getMinDelay()).append(" Hz");
                }
                sb.append(")\n");
            }
        }
        if( sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null ) {
            sb.append("-> No gyro: ELS must estimate rotation from the video frames.\n");
        }
    }

    private void appendCameras(StringBuilder sb) {
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        String [] ids;
        try {
            ids = manager.getCameraIdList();
        }
        catch(CameraAccessException e) {
            heading(sb, "Cameras");
            sb.append("Could not list cameras: ").append(e).append("\n");
            return;
        }
        for(String id : ids) {
            try {
                appendCamera(sb, id, manager.getCameraCharacteristics(id));
            }
            catch(CameraAccessException | RuntimeException e) {
                heading(sb, "Camera " + id);
                sb.append("Could not read camera: ").append(e).append("\n");
            }
        }
    }

    private void appendCamera(StringBuilder sb, String id, CameraCharacteristics chars) {
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        String facing_name = facing == null ? "unknown" :
                facing == CameraMetadata.LENS_FACING_BACK ? "back" :
                facing == CameraMetadata.LENS_FACING_FRONT ? "front" : "external";
        heading(sb, "Camera " + id + " (" + facing_name + ")");

        Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        sb.append("Hardware level: ").append(hardwareLevelName(level)).append("\n");

        int [] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if( capabilities != null ) {
            List<String> names = new ArrayList<>();
            for(int capability : capabilities) {
                names.add(capabilityName(capability));
            }
            sb.append("Capabilities: ").append(names).append("\n");
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            Set<String> physical_ids = chars.getPhysicalCameraIds();
            if( !physical_ids.isEmpty() ) {
                sb.append("Physical cameras: ").append(physical_ids).append("\n");
            }
        }

        // optics: needed to turn pixel motion into rotation angles
        Size pixel_array = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect active_array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        SizeF physical_size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float [] focal_lengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        sb.append("Sensor: pixel array ").append(pixel_array)
                .append(", active ").append(active_array == null ? "null" : active_array.width() + "x" + active_array.height())
                .append(", physical ").append(physical_size).append(" mm\n");
        sb.append("Focal lengths: ").append(Arrays.toString(focal_lengths)).append(" mm\n");
        if( physical_size != null && focal_lengths != null && focal_lengths.length > 0 ) {
            double hfov = Math.toDegrees(2.0 * Math.atan(physical_size.getWidth() / (2.0 * focal_lengths[0])));
            sb.append(String.format(Locale.US, "Horizontal field of view: %.1f deg\n", hfov));
        }
        Integer timestamp_source = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        sb.append("Timestamp source: ").append(timestamp_source == null ? "null" :
                timestamp_source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? "realtime (can sync with sensors)" : "unknown (cannot sync with sensors)").append("\n");

        // image quality controls
        sb.append("Edge (sharpening) modes: ").append(modeNames(chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), EDGE_MODE_NAMES)).append("\n");
        sb.append("Noise reduction modes: ").append(modeNames(chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), NOISE_REDUCTION_MODE_NAMES)).append("\n");
        sb.append("Tonemap modes: ").append(modeNames(chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), TONEMAP_MODE_NAMES)).append("\n");
        sb.append("Video stabilization (EIS): ").append(modeNames(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), VIDEO_STABILIZATION_MODE_NAMES)).append("\n");
        sb.append("Optical stabilization (OIS): ").append(modeNames(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), OIS_MODE_NAMES)).append("\n");

        Range<Integer> [] fps_ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        sb.append("AE target fps ranges: ").append(Arrays.toString(fps_ranges)).append("\n");

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if( map == null ) {
            sb.append("No stream configuration map\n");
            return;
        }

        Size [] video_sizes = map.getOutputSizes(MediaRecorder.class);
        sb.append("\nVideo recording sizes (MediaRecorder), max fps:\n");
        appendSizes(sb, map, video_sizes, MediaRecorder.class, -1);

        sb.append("\nPreview / GPU sizes (SurfaceTexture), max fps:\n");
        appendSizes(sb, map, map.getOutputSizes(SurfaceTexture.class), SurfaceTexture.class, -1);

        sb.append("\nAnalysis sizes (YUV_420_888), max fps:\n");
        appendSizes(sb, map, map.getOutputSizes(ImageFormat.YUV_420_888), null, ImageFormat.YUV_420_888);

        Size [] high_speed_sizes = map.getHighSpeedVideoSizes();
        if( high_speed_sizes != null && high_speed_sizes.length > 0 ) {
            sb.append("\nHigh speed video:\n");
            for(Size size : high_speed_sizes) {
                sb.append("  ").append(size).append(" ").append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(size))).append("\n");
            }
        }

        // ELS verdict: the camera must deliver the size at 30fps to a GPU (SurfaceTexture) stage
        sb.append("\nELS verdict:\n");
        appendVerdict(sb, map, "2K (2560x1440) at 30 fps", SIZE_2K);
        appendVerdict(sb, map, "1080p at 30 fps", SIZE_1080P);
        Size best = bestSixteenNine(map);
        if( best != null ) {
            sb.append("  Largest 16:9 size at 30 fps: ").append(best).append("\n");
        }
    }

    private void appendVerdict(StringBuilder sb, StreamConfigurationMap map, String label, Size size) {
        double fps = maxFps(map, SurfaceTexture.class, -1, size);
        sb.append("  ").append(label).append(": ");
        if( fps <= 0 ) {
            sb.append("NO (size not exposed)\n");
        }
        else if( fps + 0.5 < TARGET_FPS ) {
            sb.append(String.format(Locale.US, "NO (exposed, but only %.1f fps)\n", fps));
        }
        else {
            sb.append("YES\n");
        }
    }

    /** Largest 16:9 SurfaceTexture size that the camera can deliver at TARGET_FPS.
     */
    private static Size bestSixteenNine(StreamConfigurationMap map) {
        Size [] sizes = map.getOutputSizes(SurfaceTexture.class);
        if( sizes == null )
            return null;
        Size best = null;
        for(Size size : sizes) {
            if( size.getWidth() * 9 != size.getHeight() * 16 )
                continue;
            if( maxFps(map, SurfaceTexture.class, -1, size) + 0.5 < TARGET_FPS )
                continue;
            if( best == null || size.getWidth() * size.getHeight() > best.getWidth() * best.getHeight() )
                best = size;
        }
        return best;
    }

    /** Returns the max fps for this size, or 0 if the size isn't supported. Uses klass if not null,
     *  otherwise format.
     */
    private static double maxFps(StreamConfigurationMap map, Class<?> klass, int format, Size size) {
        Size [] sizes = klass != null ? map.getOutputSizes(klass) : map.getOutputSizes(format);
        if( sizes == null || !Arrays.asList(sizes).contains(size) )
            return 0;
        long min_duration = klass != null ? map.getOutputMinFrameDuration(klass, size) : map.getOutputMinFrameDuration(format, size);
        if( min_duration <= 0 )
            return 0; // unknown
        return 1.0e9 / min_duration;
    }

    private static void appendSizes(StringBuilder sb, StreamConfigurationMap map, Size [] sizes, Class<?> klass, int format) {
        if( sizes == null || sizes.length == 0 ) {
            sb.append("  none\n");
            return;
        }
        sizes = sizes.clone();
        Arrays.sort(sizes, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Integer.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight());
            }
        });
        for(Size size : sizes) {
            double fps = maxFps(map, klass, format, size);
            sb.append("  ").append(size).append(fps > 0 ? String.format(Locale.US, " @ %.1f", fps) : " @ ?").append("\n");
        }
    }

    private void appendCamcorderProfiles(StringBuilder sb) {
        heading(sb, "Camcorder profiles (back camera)");
        int [] qualities = {CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P};
        String [] names = {"2160p", "1080p", "720p"};
        int camera_id = 0;
        for(int i=0;i<qualities.length;i++) {
            sb.append(names[i]).append(": ");
            if( !CamcorderProfile.hasProfile(camera_id, qualities[i]) ) {
                sb.append("no\n");
                continue;
            }
            try {
                CamcorderProfile profile = CamcorderProfile.get(camera_id, qualities[i]);
                sb.append(profile.videoFrameWidth).append("x").append(profile.videoFrameHeight)
                        .append(" @ ").append(profile.videoFrameRate).append(" fps, ")
                        .append(profile.videoBitRate / 1000000).append(" Mbps\n");
            }
            catch(RuntimeException e) {
                sb.append("error ").append(e).append("\n");
            }
        }
    }

    private void appendEncoders(StringBuilder sb) {
        heading(sb, "Video encoders");
        String [] mime_types = {"video/avc", "video/hevc"};
        String [] mime_names = {"H.264", "HEVC"};
        MediaCodecInfo [] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for(int i=0;i<mime_types.length;i++) {
            boolean found = false;
            for(MediaCodecInfo info : infos) {
                if( !info.isEncoder() || !Arrays.asList(info.getSupportedTypes()).contains(mime_types[i]) )
                    continue;
                found = true;
                sb.append(mime_names[i]).append(": ").append(info.getName());
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                    sb.append(info.isHardwareAccelerated() ? " (hardware)" : " (software)");
                }
                sb.append("\n");
                try {
                    MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(mime_types[i]).getVideoCapabilities();
                    sb.append("  max ").append(caps.getSupportedWidths().getUpper()).append("x").append(caps.getSupportedHeights().getUpper())
                            .append(", bitrate up to ").append(caps.getBitrateRange().getUpper() / 1000000).append(" Mbps\n");
                    sb.append("  2560x1440@30: ").append(caps.areSizeAndRateSupported(SIZE_2K.getWidth(), SIZE_2K.getHeight(), TARGET_FPS) ? "yes" : "no")
                            .append(", 1920x1080@30: ").append(caps.areSizeAndRateSupported(SIZE_1080P.getWidth(), SIZE_1080P.getHeight(), TARGET_FPS) ? "yes" : "no").append("\n");
                }
                catch(RuntimeException e) {
                    sb.append("  error reading capabilities: ").append(e).append("\n");
                }
            }
            if( !found ) {
                sb.append(mime_names[i]).append(": none\n");
            }
        }
    }

    private static final String [] EDGE_MODE_NAMES = {"off", "fast", "high quality", "zero shutter lag"};
    private static final String [] NOISE_REDUCTION_MODE_NAMES = {"off", "fast", "high quality", "minimal", "zero shutter lag"};
    private static final String [] TONEMAP_MODE_NAMES = {"contrast curve", "fast", "high quality", "gamma value", "preset curve"};
    private static final String [] VIDEO_STABILIZATION_MODE_NAMES = {"off", "on", "preview stabilization"};
    private static final String [] OIS_MODE_NAMES = {"off", "on"};

    private static String modeNames(int [] modes, String [] names) {
        if( modes == null )
            return "not reported";
        List<String> result = new ArrayList<>();
        for(int mode : modes) {
            result.add(mode >= 0 && mode < names.length ? names[mode] : String.valueOf(mode));
        }
        Collections.sort(result);
        return result.toString();
    }

    private static String hardwareLevelName(Integer level) {
        if( level == null )
            return "unknown";
        switch( level ) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return String.valueOf(level);
        }
    }

    private static String capabilityName(int capability) {
        switch( capability ) {
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                return "backward compatible";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                return "manual sensor";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                return "manual post processing";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                return "raw";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS:
                return "read sensor settings";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE:
                return "burst capture";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                return "high speed video";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                return "logical multi camera";
            default:
                return String.valueOf(capability);
        }
    }
}
