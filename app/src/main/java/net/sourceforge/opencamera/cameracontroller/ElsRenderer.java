package net.sourceforge.opencamera.cameracontroller;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ELS GPU stage. Sits between the camera and the video recorder:
 *
 *   camera --> (input surface) --> GPU shader --> recorder surface
 *
 * The shader (1) removes sharpening halos and crunchy edges that budget phones add, and
 * (2) applies gentle colour correction (saturation, vibrance, contrast, warmth).
 *
 * Stage 1 only does this for the RECORDED video; the live preview is not processed.
 * This class is also where the stabilization warp will be added in a later stage.
 *
 * All OpenGL work happens on one private thread. The constructor blocks until the GPU
 * side is ready, and throws a RuntimeException if anything fails, so the caller can fall
 * back to recording normally.
 */
public class ElsRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "ElsRenderer";

    public static final int MODE_OFF = 0;
    public static final int MODE_NATURAL = 1;
    public static final int MODE_VIVID = 2;
    public static final int MODE_SOFT = 3;
    public static final int MODE_DESHARPEN = 4;

    // not defined in EGL14: EGL_RECORDABLE_ANDROID tells EGL the surface feeds a video encoder
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    /** Preset values. Order: dering, soften, saturation, vibrance, contrast, warmth.
     *  These are starting values to be tuned by eye on the real phone.
     */
    private static final float[][] PRESETS = {
            {0.0f, 0.00f, 1.00f, 0.00f, 1.00f, 0.000f}, // off (pass-through)
            {0.7f, 0.15f, 1.05f, 0.20f, 1.04f, 0.008f}, // natural
            {0.7f, 0.10f, 1.12f, 0.30f, 1.10f, 0.012f}, // vivid
            {0.8f, 0.35f, 1.00f, 0.10f, 1.00f, 0.000f}, // soft
            {1.0f, 0.30f, 1.00f, 0.00f, 1.00f, 0.000f}, // de-sharpen only
    };

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTex = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform float uDering;\n" +
            "uniform float uSoften;\n" +
            "uniform float uSat;\n" +
            "uniform float uVib;\n" +
            "uniform float uContrast;\n" +
            "uniform float uWarm;\n" +
            "void main() {\n" +
            "    vec3 c = texture2D(sTex, vTex).rgb;\n" +
            "    if (uDering > 0.0 || uSoften > 0.0) {\n" +
            "        vec3 l = texture2D(sTex, vTex - vec2(uTexel.x, 0.0)).rgb;\n" +
            "        vec3 r = texture2D(sTex, vTex + vec2(uTexel.x, 0.0)).rgb;\n" +
            "        vec3 up = texture2D(sTex, vTex - vec2(0.0, uTexel.y)).rgb;\n" +
            "        vec3 dn = texture2D(sTex, vTex + vec2(0.0, uTexel.y)).rgb;\n" +
            "        // halo removal: a sharpened edge overshoots its neighbours, so pull each\n" +
            "        // pixel back into the range of the pixels around it\n" +
            "        vec3 mn = min(min(l, r), min(up, dn));\n" +
            "        vec3 mx = max(max(l, r), max(up, dn));\n" +
            "        c = mix(c, clamp(c, mn, mx), uDering);\n" +
            "        c = mix(c, (l + r + up + dn) * 0.25, uSoften);\n" +
            "    }\n" +
            "    c.r += uWarm;\n" +
            "    c.b -= uWarm;\n" +
            "    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));\n" +
            "    // vibrance: lift dull colours more than colours that are already strong\n" +
            "    float amount = uSat + uVib * (1.0 - clamp(chroma * 2.0, 0.0, 1.0));\n" +
            "    c = mix(vec3(luma), c, amount);\n" +
            "    c = (c - 0.5) * uContrast + 0.5;\n" +
            "    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n" +
            "}\n";

    private static final float[] POSITIONS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEXCOORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final Surface outputSurface;
    private final float[] preset;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean released = false;

    // GL-thread state
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program = 0;
    private int oesTexture = 0;
    private int aPositionLoc, aTexCoordLoc;
    private int uTexMatrixLoc, uTexLoc, uTexelLoc, uDeringLoc, uSoftenLoc, uSatLoc, uVibLoc, uContrastLoc, uWarmLoc;
    private FloatBuffer positionBuffer;
    private FloatBuffer texCoordBuffer;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private int width, height;
    private final float[] texMatrix = new float[16];
    private long lastTimestamp = Long.MIN_VALUE;
    private boolean clockChecked = false;
    private long timestampOffset = 0;
    private long frameCount = 0;
    private long lastLogTime = 0;

    /** Converts the value stored in the preference to a mode. */
    public static int modeFromPref(String value) {
        if( "natural".equals(value) )
            return MODE_NATURAL;
        else if( "vivid".equals(value) )
            return MODE_VIVID;
        else if( "soft".equals(value) )
            return MODE_SOFT;
        else if( "desharpen".equals(value) )
            return MODE_DESHARPEN;
        return MODE_OFF;
    }

    public static String modeName(int mode) {
        switch( mode ) {
            case MODE_NATURAL:
                return "Natural";
            case MODE_VIVID:
                return "Vivid";
            case MODE_SOFT:
                return "Soft";
            case MODE_DESHARPEN:
                return "De-sharpen only";
            default:
                return "Off";
        }
    }

    /**
     * @param outputSurface The recorder's input surface. Processed frames are drawn into it.
     * @param mode          One of the MODE_ constants.
     * @throws RuntimeException if the GPU side could not be set up.
     */
    public ElsRenderer(Surface outputSurface, int mode) {
        this.outputSurface = outputSurface;
        if( mode < 0 || mode >= PRESETS.length )
            mode = MODE_OFF;
        this.preset = PRESETS[mode];

        thread = new HandlerThread("ElsRenderer", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());

        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    initGl();
                }
                catch(Throwable t) {
                    error[0] = t;
                }
                latch.countDown();
            }
        });
        boolean finished = false;
        try {
            finished = latch.await(5, TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if( !finished || error[0] != null ) {
            release();
            throw new RuntimeException("ElsRenderer setup failed", error[0]);
        }
    }

    /** The surface the camera should write to. */
    public Surface getInputSurface() {
        return inputSurface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Stops processing and frees everything. Safe to call more than once. */
    public void release() {
        if( released )
            return;
        released = true;
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    teardownGl();
                }
                catch(Throwable t) {
                    Log.e(TAG, "teardown failed: " + t);
                }
                latch.countDown();
            }
        });
        if( posted ) {
            try {
                latch.await(3, TimeUnit.SECONDS);
            }
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread.quitSafely();
    }

    // ---- everything below runs on the GL thread ----

    private void initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if( display == EGL14.EGL_NO_DISPLAY )
            throw new RuntimeException("no EGL display");
        int[] version = new int[2];
        if( !EGL14.eglInitialize(display, version, 0, version, 1) )
            throw new RuntimeException("eglInitialize failed");

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if( !EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0 )
            throw new RuntimeException("no suitable EGL config");
        EGLConfig config = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if( context == null || context == EGL14.EGL_NO_CONTEXT )
            throw new RuntimeException("eglCreateContext failed");

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, config, outputSurface, surfaceAttribs, 0);
        if( eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE )
            throw new RuntimeException("eglCreateWindowSurface failed");
        if( !EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context) )
            throw new RuntimeException("eglMakeCurrent failed");

        // the recorder surface already knows the video size
        int[] size = new int[1];
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, size, 0);
        width = size[0];
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, size, 0);
        height = size[0];
        if( width <= 0 || height <= 0 || width > 8192 || height > 8192 )
            throw new RuntimeException("unexpected recorder surface size: " + width + " x " + height);
        Log.d(TAG, "output size: " + width + " x " + height);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uTexLoc = GLES20.glGetUniformLocation(program, "sTex");
        uTexelLoc = GLES20.glGetUniformLocation(program, "uTexel");
        uDeringLoc = GLES20.glGetUniformLocation(program, "uDering");
        uSoftenLoc = GLES20.glGetUniformLocation(program, "uSoften");
        uSatLoc = GLES20.glGetUniformLocation(program, "uSat");
        uVibLoc = GLES20.glGetUniformLocation(program, "uVib");
        uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast");
        uWarmLoc = GLES20.glGetUniformLocation(program, "uWarm");

        positionBuffer = toFloatBuffer(POSITIONS);
        texCoordBuffer = toFloatBuffer(TEXCOORDS);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTexture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setDefaultBufferSize(width, height);
        inputSurface = new Surface(surfaceTexture);
        surfaceTexture.setOnFrameAvailableListener(this, handler);

        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // called on the GL thread, as we passed our handler to setOnFrameAvailableListener
        drawFrame();
    }

    private void drawFrame() {
        if( released || surfaceTexture == null )
            return;
        try {
            surfaceTexture.updateTexImage();
            long timestamp = surfaceTexture.getTimestamp();
            if( timestamp == lastTimestamp ) {
                return; // no new frame
            }
            lastTimestamp = timestamp;
            surfaceTexture.getTransformMatrix(texMatrix);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glUniform1i(uTexLoc, 0);
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);
            GLES20.glUniform2f(uTexelLoc, 1.0f / width, 1.0f / height);
            GLES20.glUniform1f(uDeringLoc, preset[0]);
            GLES20.glUniform1f(uSoftenLoc, preset[1]);
            GLES20.glUniform1f(uSatLoc, preset[2]);
            GLES20.glUniform1f(uVibLoc, preset[3]);
            GLES20.glUniform1f(uContrastLoc, preset[4]);
            GLES20.glUniform1f(uWarmLoc, preset[5]);

            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, positionBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);

            // The recorder expects timestamps on the same clock as the audio (monotonic).
            // Some phones stamp camera frames on a different clock; if the first frame is far
            // from "now", remember the gap and correct every frame by it.
            if( !clockChecked ) {
                clockChecked = true;
                long gap = System.nanoTime() - timestamp;
                if( Math.abs(gap) > 1000000000L ) {
                    timestampOffset = gap;
                    Log.d(TAG, "camera clock differs from monotonic clock, offset: " + timestampOffset);
                }
            }
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestamp + timestampOffset);
            EGL14.eglSwapBuffers(display, eglSurface);

            frameCount++;
            long now = System.nanoTime();
            if( now - lastLogTime > 2000000000L ) {
                lastLogTime = now;
                Log.d(TAG, "frames processed so far: " + frameCount);
            }
        }
        catch(RuntimeException e) {
            Log.e(TAG, "failed to draw frame: " + e);
        }
    }

    private void teardownGl() {
        if( surfaceTexture != null ) {
            surfaceTexture.setOnFrameAvailableListener(null);
        }
        if( inputSurface != null ) {
            inputSurface.release();
            inputSurface = null;
        }
        if( surfaceTexture != null ) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if( display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE ) {
            // GL objects can only be deleted while the context is current
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
            if( program != 0 ) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if( oesTexture != 0 ) {
                GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
                oesTexture = 0;
            }
        }
        if( display != EGL14.EGL_NO_DISPLAY ) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if( eglSurface != EGL14.EGL_NO_SURFACE ) {
                EGL14.eglDestroySurface(display, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if( context != EGL14.EGL_NO_CONTEXT ) {
                EGL14.eglDestroyContext(display, context);
                context = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static FloatBuffer toFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        if( program == 0 )
            throw new RuntimeException("glCreateProgram failed");
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if( status[0] != GLES20.GL_TRUE ) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("shader link failed: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if( shader == 0 )
            throw new RuntimeException("glCreateShader failed");
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if( status[0] != GLES20.GL_TRUE ) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return shader;
    }
}
