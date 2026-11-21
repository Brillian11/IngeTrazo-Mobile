package com.example.ui.ar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig as KhronosEglConfig
import javax.microedition.khronos.egl.EGLDisplay as KhronosEglDisplay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ArCaptureMode(val label: String) {
    PHOTO("Photo"),
    VIDEO("Video")
}

data class ArCapturedMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val durationMs: Long = 0L,
    val savedToGallery: Boolean = true
)

/**
 * Pure-Kotlin helper computing encoder-safe H.264 video dimensions (multiples of 16)
 * and formatted recording timers.
 */
object ArVideoSizeCalculator {
    const val DEFAULT_MAX_LONG_EDGE = 1280
    const val MIN_EDGE = 320

    data class VideoSize(val width: Int, val height: Int)

    fun computeEncoderSize(
        viewportWidth: Int,
        viewportHeight: Int,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE
    ): VideoSize {
        val vw = viewportWidth.coerceAtLeast(1)
        val vh = viewportHeight.coerceAtLeast(1)
        val longEdge = max(vw, vh).toFloat()
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1.0f
        val rawW = (vw * scale).roundToInt().coerceAtLeast(MIN_EDGE)
        val rawH = (vh * scale).roundToInt().coerceAtLeast(MIN_EDGE)
        // Round to multiples of 16 for universal hardware H.264 encoder compatibility
        val alignedW = ((rawW + 8) / 16 * 16).coerceIn(MIN_EDGE, maxLongEdge)
        val alignedH = ((rawH + 8) / 16 * 16).coerceIn(MIN_EDGE, maxLongEdge)
        return VideoSize(alignedW, alignedH)
    }

    fun formatElapsedDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun sanitizeFileSlug(title: String): String {
        val cleaned = title
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .take(28)
        return cleaned.ifBlank { "AR_Model" }
    }
}

/**
 * Custom EGLConfigChooser requesting EGL_RECORDABLE_ANDROID so MediaCodec input surfaces can
 * share the OpenGL ES 3.0 context cleanly on mobile GPUs without interrupting ARCore passthrough.
 */
class RecordableEglConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: KhronosEglDisplay): KhronosEglConfig {
        val eglOpenglEs3Bit = 0x0040
        val eglRecordableAndroid = 0x3142

        val candidateSpecLists = listOf(
            // Preferred: ES 3 + RGBA8888 + Depth 16 + Recordable
            intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, eglOpenglEs3Bit,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 0,
                eglRecordableAndroid, 1,
                EGL10.EGL_NONE
            ),
            // Fallback 1: ES 2/3 + RGBA8888 + Depth 16 + Recordable
            intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 0,
                eglRecordableAndroid, 1,
                EGL10.EGL_NONE
            ),
            // Fallback 2: Standard RGBA8888 + Depth 16 without recordable bit
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE
            )
        )

        val numConfig = IntArray(1)
        val configs = arrayOfNulls<KhronosEglConfig>(1)
        for (spec in candidateSpecLists) {
            if (egl.eglChooseConfig(display, spec, configs, 1, numConfig) &&
                numConfig[0] > 0 && configs[0] != null
            ) {
                return configs[0]!!
            }
        }
        throw IllegalStateException("Unable to find a suitable OpenGL ES 3.0 EGLConfig for AR view")
    }
}

/**
 * Hardware-accelerated OpenGL ES 3.0 AR Video Recorder.
 * Renders the AR frame once into an offscreen FBO texture when recording is active, then blits the
 * finished frame to BOTH the live screen and a MediaCodec H.264 EGL window surface—zero double
 * 3D rendering, zero camera glitches, and zero overhead when idle.
 */
class ArGlVideoRecorder {

    @Volatile
    private var recordingActive: Boolean = false

    @Volatile
    private var pendingStartFile: File? = null

    @Volatile
    private var pendingStartErrorCb: ((String) -> Unit)? = null

    @Volatile
    private var pendingStopCallback: ((file: File?, durationMs: Long, error: String?) -> Unit)? = null

    private var activeOutputFile: File? = null
    private var activeErrorCb: ((String) -> Unit)? = null
    private var startElapsedMs: Long = 0L
    private var startPresentationTimeNs: Long = Long.MIN_VALUE
    private var lastPresentationTimeNs: Long = Long.MIN_VALUE
    private var encodedFrameCount: Int = 0

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    private var recFboId: Int = 0
    private var recColorTexId: Int = 0
    private var recDepthRboId: Int = 0
    private var copyProgram: Int = 0
    private var uCopyTex: Int = 0

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var videoTrackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private val bufferInfo = MediaCodec.BufferInfo()

    private var savedDrawSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var savedReadSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val blitQuadCoords: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val blitTexCoords: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

    val isRecording: Boolean
        get() = recordingActive || pendingStartFile != null

    fun requestStartRecording(
        outputFile: File,
        onError: (String) -> Unit
    ) {
        pendingStopCallback = null
        pendingStartErrorCb = onError
        pendingStartFile = outputFile
    }

    fun requestStopRecording(
        onFinished: (file: File?, durationMs: Long, error: String?) -> Unit
    ) {
        if (!recordingActive && pendingStartFile == null) {
            onFinished(null, 0L, null)
            return
        }
        pendingStartFile = null
        pendingStopCallback = onFinished
    }

    /**
     * Called on the GL thread at the start of [ArCoreRenderer.onDrawFrame].
     * Processes pending start/stop requests and binds the offscreen recording FBO if active.
     * Returns true when the caller should draw into the bound FBO and call [endFrameAndEncode].
     */
    fun beginFrame(viewportWidth: Int, viewportHeight: Int): Boolean {
        val stopCb = pendingStopCallback
        if (stopCb != null) {
            pendingStopCallback = null
            finishRecordingOnGlThread(stopCb)
            return false
        }

        val startFile = pendingStartFile
        if (startFile != null) {
            pendingStartFile = null
            val errCb = pendingStartErrorCb
            pendingStartErrorCb = null
            startRecordingOnGlThread(startFile, viewportWidth, viewportHeight, errCb)
        }

        if (!recordingActive || encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            return false
        }

        if (!ensureRecordingFbo(viewportWidth, viewportHeight) || copyProgram == 0) {
            abortRecordingWithError("Unable to initialize OpenGL recording buffer.")
            return false
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, recFboId)
        GLES30.glViewport(0, 0, fboWidth, fboHeight)
        GLES30.glClearColor(0.02f, 0.03f, 0.05f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        return true
    }

    /**
     * Called on the GL thread after the AR frame has finished drawing into [recFboId].
     * Blits the completed frame to the live display framebuffer (0) AND to the MediaCodec
     * EGL window surface with presentation timestamp.
     */
    fun endFrameAndEncode(viewportWidth: Int, viewportHeight: Int, frameTimestampNs: Long) {
        // 1. Always restore default framebuffer & blit to the live display first
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        drawBlitQuad()

        if (!recordingActive || encoderEglSurface == EGL14.EGL_NO_SURFACE) return

        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        savedDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        savedReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT ||
            savedDrawSurface == EGL14.EGL_NO_SURFACE
        ) {
            return
        }

        try {
            if (!EGL14.eglMakeCurrent(display, encoderEglSurface, encoderEglSurface, context)) {
                abortRecordingWithError("Video encoder surface lost.")
                return
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, videoWidth, videoHeight)
            drawBlitQuad()

            val nowNs = if (frameTimestampNs > 0L) frameTimestampNs else System.nanoTime()
            if (startPresentationTimeNs == Long.MIN_VALUE) {
                startPresentationTimeNs = nowNs
            }
            var ptsNs = (nowNs - startPresentationTimeNs).coerceAtLeast(0L)
            if (lastPresentationTimeNs != Long.MIN_VALUE && ptsNs <= lastPresentationTimeNs) {
                ptsNs = lastPresentationTimeNs + 16_666_667L
            }
            lastPresentationTimeNs = ptsNs

            EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, encoderEglSurface)
            encodedFrameCount++
            drainEncoder(endOfStream = false)
        } catch (t: Throwable) {
            Log.e(TAG, "Error encoding AR video frame", t)
            abortRecordingWithError(t.localizedMessage ?: "Video encoding failed.")
        } finally {
            // Always restore the display EGL surface so the live AR viewport never glitches
            EGL14.eglMakeCurrent(display, savedDrawSurface, savedReadSurface, context)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    fun onGlContextRecreated() {
        recFboId = 0
        recColorTexId = 0
        recDepthRboId = 0
        copyProgram = 0
        uCopyTex = 0
        fboWidth = 0
        fboHeight = 0
    }

    fun releaseGlResources() {
        if (recordingActive) {
            finishRecordingOnGlThread(null)
        }
        deleteFboAndShader()
    }

    private fun startRecordingOnGlThread(
        outputFile: File,
        viewportWidth: Int,
        viewportHeight: Int,
        onError: ((String) -> Unit)?
    ) {
        try {
            if (recordingActive) {
                finishRecordingOnGlThread(null)
            }
            val size = ArVideoSizeCalculator.computeEncoderSize(viewportWidth, viewportHeight)
            videoWidth = size.width
            videoHeight = size.height

            val display = EGL14.eglGetCurrentDisplay()
            val context = EGL14.eglGetCurrentContext()
            if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) {
                onError?.invoke("OpenGL context unavailable for recording.")
                return
            }

            val eglConfig = resolveEncoderEglConfig(display, context)
            if (eglConfig == null) {
                onError?.invoke("Device GPU does not expose a recordable EGL configuration.")
                return
            }

            if (copyProgram == 0) {
                copyProgram = compileProgram(COPY_VERTEX, COPY_FRAGMENT)
                if (copyProgram != 0) {
                    uCopyTex = GLES30.glGetUniformLocation(copyProgram, "uTex")
                }
            }
            if (copyProgram == 0) {
                onError?.invoke("Failed to compile video blit shader.")
                return
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoWidth,
                videoHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                val targetBitrate = (videoWidth * videoHeight * 6).coerceIn(2_500_000, 10_000_000)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurf = EGL14.eglCreateWindowSurface(display, eglConfig, surface, surfaceAttribs, 0)
            if (eglSurf == null || eglSurf == EGL14.EGL_NO_SURFACE) {
                codec.stop()
                codec.release()
                surface.release()
                onError?.invoke("Could not create EGL surface for video recorder.")
                return
            }

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            mediaCodec = codec
            mediaMuxer = muxer
            inputSurface = surface
            encoderEglDisplay = display
            encoderEglSurface = eglSurf
            activeOutputFile = outputFile
            activeErrorCb = onError
            videoTrackIndex = -1
            muxerStarted = false
            startElapsedMs = SystemClock.elapsedRealtime()
            startPresentationTimeNs = Long.MIN_VALUE
            lastPresentationTimeNs = Long.MIN_VALUE
            encodedFrameCount = 0
            recordingActive = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start AR video recording", t)
            cleanupEncoderResources()
            onError?.invoke(t.localizedMessage ?: "Unable to start AR video recording.")
        }
    }

    private fun finishRecordingOnGlThread(
        callback: ((file: File?, durationMs: Long, error: String?) -> Unit)?
    ) {
        val outputFile = activeOutputFile
        val elapsedMs = if (startElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        var errorMessage: String? = null
        val hadFrames = encodedFrameCount > 0

        try {
            if (recordingActive && mediaCodec != null) {
                drainEncoder(endOfStream = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error finalizing AR video recording", t)
            errorMessage = t.localizedMessage ?: "Failed to finalize video recording."
        } finally {
            val savedValidVideo = muxerStarted && hadFrames && outputFile != null && outputFile.exists() && outputFile.length() > 0L
            cleanupEncoderResources()
            if (callback != null) {
                val finalFile = if (savedValidVideo && errorMessage == null) outputFile else null
                if (finalFile == null && errorMessage == null && !hadFrames) {
                    errorMessage = "Recording too short — keep AR view active for at least 1 second."
                }
                callback(finalFile, elapsedMs, errorMessage)
            }
        }
    }

    private fun abortRecordingWithError(message: String) {
        val errCb = activeErrorCb
        cleanupEncoderResources()
        errCb?.invoke(message)
    }

    private fun cleanupEncoderResources() {
        recordingActive = false
        activeErrorCb = null
        activeOutputFile = null
        try {
            mediaCodec?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Throwable) {
        }
        mediaCodec = null

        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            mediaMuxer?.release()
        } catch (_: Throwable) {
        }
        mediaMuxer = null
        muxerStarted = false
        videoTrackIndex = -1

        if (encoderEglDisplay != EGL14.EGL_NO_DISPLAY && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglDestroySurface(encoderEglDisplay, encoderEglSurface)
            } catch (_: Throwable) {
            }
        }
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        encoderEglDisplay = EGL14.EGL_NO_DISPLAY

        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        startElapsedMs = 0L
        startPresentationTimeNs = Long.MIN_VALUE
        lastPresentationTimeNs = Long.MIN_VALUE
        encodedFrameCount = 0
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        if (endOfStream) {
            codec.signalEndOfInputStream()
        }

        val timeoutUs = if (endOfStream) 10_000L else 0L
        var loops = 0
        while (loops < 64) {
            loops++
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        val newFormat = codec.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted && videoTrackIndex >= 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun ensureRecordingFbo(width: Int, height: Int): Boolean {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (recFboId != 0 && fboWidth == w && fboHeight == h) {
            return true
        }
        deleteFboOnly()

        val fbos = IntArray(1)
        val texs = IntArray(1)
        val rbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        GLES30.glGenTextures(1, texs, 0)
        GLES30.glGenRenderbuffers(1, rbos, 0)

        recFboId = fbos[0]
        recColorTexId = texs[0]
        recDepthRboId = rbos[0]
        fboWidth = w
        fboHeight = h

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, recColorTexId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            w,
            h,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, recDepthRboId)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, w, h)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, recFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            recColorTexId,
            0
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER,
            recDepthRboId
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            deleteFboOnly()
            return false
        }
        return true
    }

    private fun drawBlitQuad() {
        if (copyProgram == 0 || recColorTexId == 0) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, recColorTexId)
        GLES30.glUniform1i(uCopyTex, 1)

        blitQuadCoords.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, blitQuadCoords)
        blitTexCoords.position(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, blitTexCoords)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glUseProgram(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun deleteFboOnly() {
        if (recFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(recFboId), 0)
            recFboId = 0
        }
        if (recColorTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(recColorTexId), 0)
            recColorTexId = 0
        }
        if (recDepthRboId != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(recDepthRboId), 0)
            recDepthRboId = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    private fun deleteFboAndShader() {
        deleteFboOnly()
        if (copyProgram != 0) {
            GLES30.glDeleteProgram(copyProgram)
            copyProgram = 0
        }
    }

    private fun resolveEncoderEglConfig(display: EGLDisplay, context: EGLContext): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        // First try exact config ID matching the current GL context
        val configId = IntArray(1)
        if (EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configId, 0) &&
            configId[0] != 0
        ) {
            val byId = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
            if (EGL14.eglChooseConfig(display, byId, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
            ) {
                return configs[0]
            }
        }

        // Fallback: choose RGBA8888 recordable config
        val eglRecordableAndroid = 0x3142
        val recordableSpec = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            eglRecordableAndroid, 1,
            EGL14.EGL_NONE
        )
        if (EGL14.eglChooseConfig(display, recordableSpec, 0, configs, 0, 1, numConfigs, 0) &&
            numConfigs[0] > 0 && configs[0] != null
        ) {
            return configs[0]
        }
        return null
    }

    private fun compileProgram(vs: String, fs: String): Int {
        val status = IntArray(1)
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        GLES30.glShaderSource(v, vs)
        GLES30.glCompileShader(v)
        GLES30.glGetShaderiv(v, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteShader(v)
            return 0
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        GLES30.glShaderSource(f, fs)
        GLES30.glCompileShader(f)
        GLES30.glGetShaderiv(f, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return 0
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (status[0] == 0) {
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    companion object {
        private const val TAG = "ArGlVideoRecorder"

        internal const val COPY_VERTEX = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
}"""

        internal const val COPY_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
in vec2 vTex;
uniform mediump sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vTex);
}"""
    }
}

/**
 * Zero-permission Scoped Storage MediaStore & PixelCopy helper for saving, viewing,
 * and sharing AR photos and videos.
 */
object ArMediaStoreHelper {

    private fun timestampTag(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun createTempVideoFile(context: Context, modelTitle: String): File {
        val dir = File(context.cacheDir, "ar_media").apply { mkdirs() }
        val slug = ArVideoSizeCalculator.sanitizeFileSlug(modelTitle)
        return File(dir, "IngeTrazo_${slug}_${timestampTag()}.mp4")
    }

    fun captureGlSurfacePhoto(
        glSurfaceView: GLSurfaceView,
        context: Context,
        modelTitle: String,
        onComplete: (media: ArCapturedMedia?, error: String?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width <= 0 || height <= 0) {
            onComplete(null, "AR view is not ready to capture yet.")
            return
        }

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            onComplete(null, t.localizedMessage ?: "Insufficient memory to capture photo.")
            return
        }

        val copyThread = HandlerThread("ArPhotoPixelCopy").apply { start() }
        val copyHandler = Handler(copyThread.looper)

        try {
            PixelCopy.request(
                glSurfaceView,
                bitmap,
                { copyResult ->
                    try {
                        if (copyResult == PixelCopy.SUCCESS) {
                            val saved = savePhotoToGallery(context, bitmap, modelTitle)
                            mainHandler.post {
                                if (saved != null) {
                                    onComplete(saved, null)
                                } else {
                                    onComplete(null, "Could not save photo to gallery.")
                                }
                            }
                        } else {
                            mainHandler.post {
                                onComplete(null, "Camera frame capture failed (code $copyResult).")
                            }
                        }
                    } catch (t: Throwable) {
                        mainHandler.post {
                            onComplete(null, t.localizedMessage ?: "Failed to save AR photo.")
                        }
                    } finally {
                        bitmap.recycle()
                        copyThread.quitSafely()
                    }
                },
                copyHandler
            )
        } catch (t: Throwable) {
            bitmap.recycle()
            copyThread.quitSafely()
            onComplete(null, t.localizedMessage ?: "Unable to capture AR photo.")
        }
    }

    fun savePhotoToGallery(
        context: Context,
        bitmap: Bitmap,
        modelTitle: String
    ): ArCapturedMedia? {
        val slug = ArVideoSizeCalculator.sanitizeFileSlug(modelTitle)
        val fileName = "IngeTrazo_${slug}_${timestampTag()}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/IngeTrazo AR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ArCapturedMedia(
                    uri = uri,
                    isVideo = false,
                    displayName = fileName,
                    savedToGallery = true
                )
            } catch (_: Throwable) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir,
                "IngeTrazo AR"
            ).apply { mkdirs() }
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            ArCapturedMedia(
                uri = uri,
                isVideo = false,
                displayName = fileName,
                savedToGallery = false
            )
        }
    }

    fun publishVideoToGallery(
        context: Context,
        tempVideoFile: File,
        modelTitle: String,
        durationMs: Long
    ): ArCapturedMedia? {
        if (!tempVideoFile.exists() || tempVideoFile.length() <= 0L) return null
        val slug = ArVideoSizeCalculator.sanitizeFileSlug(modelTitle)
        val fileName = "IngeTrazo_${slug}_${timestampTag()}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/IngeTrazo AR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tempVideoFile).use { input ->
                        input.copyTo(out)
                    }
                } ?: return null
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                tempVideoFile.delete()
                ArCapturedMedia(
                    uri = uri,
                    isVideo = true,
                    displayName = fileName,
                    durationMs = durationMs,
                    savedToGallery = true
                )
            } catch (_: Throwable) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir,
                "IngeTrazo AR"
            ).apply { mkdirs() }
            val destFile = File(dir, fileName)
            FileInputStream(tempVideoFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }
            tempVideoFile.delete()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
            ArCapturedMedia(
                uri = uri,
                isVideo = true,
                displayName = fileName,
                durationMs = durationMs,
                savedToGallery = false
            )
        }
    }

    fun openCapturedMedia(context: Context, media: ArCapturedMedia) {
        val mime = if (media.isVideo) "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Open AR Capture")) }
    }

    fun shareCapturedMedia(context: Context, media: ArCapturedMedia) {
        val mime = if (media.isVideo) "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, media.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share AR Capture")) }
    }
}
