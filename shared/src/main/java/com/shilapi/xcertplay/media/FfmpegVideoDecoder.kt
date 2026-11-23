package com.shilapi.xcertplay.media

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.diagnostics.VideoPipelineTrace
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The decoder operations [AndroidMediaSink] drives on one stream, so the MediaCodec worker and the
 * FFmpeg software decoder below are interchangeable from the sink's point of view.
 */
internal interface StreamVideoDecoder : Closeable {
    fun start()
    fun configure(codec: VideoCodec, codecData: ByteArray)
    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L)
    fun setSurface(surface: Surface?)
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean
    val exited: Boolean
    fun awaitExitUntil(deadlineNanos: Long): Boolean
    fun refreshPicture()
    fun liveCounters(): LiveVideoCounters
}

/**
 * FFmpeg software H.264 decoder for the main CarPlay screen, the alternative the "video decoder"
 * setting selects. One worker owns the native decoder, the EGL surface and the frame queue; each
 * decoded frame is copied plane by plane out of the native side and drawn through a YUV shader,
 * which the weakest head-unit GPUs still handle at 720p. [threads] frame threads raise throughput
 * on multi-core units at the cost of one frame of extra delay each.
 */
internal class FfmpegVideoDecoder(
    type: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val threads: Int,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    /** Called on the worker as its last step, after it released the decoder and the surface. */
    private val onExit: (StreamVideoDecoder) -> Unit,
) : StreamVideoDecoder {
    private val queue = VideoDecodeQueue()
    private val stats = VideoStats("")
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private val renderer = FfmpegGlRenderer(report)
    @Volatile private var running = true
    private var handle = 0L
    private var lastConfig: VideoJob.Config? = null
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private var lastKeyFrameRequestNs = 0L
    // Frame-plane buffers, allocated once the stream's real dimensions are known.
    private var planeY: ByteBuffer? = null
    private var planeU: ByteBuffer? = null
    private var planeV: ByteBuffer? = null
    private var frameInfo = IntArray(4)
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    private var lastColorspace = 0
    private var lastColorRange = 0

    private val liveReceived = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()

    init {
        // The sink hands the stream's current surface at construction; later changes arrive as jobs.
        if (surface != null) renderer.setSurface(surface)
    }

    private val thread = Thread(::run, "carplay-video-ffmpeg").apply { isDaemon = true }

    override fun start() {
        thread.start()
    }

    override fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    override fun submit(nalus: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    override fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    override fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    override val exited: Boolean get() = !thread.isAlive

    override fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    override fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    override fun liveCounters(): LiveVideoCounters = LiveVideoCounters(
        received = liveReceived.get(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
    )

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        VideoPipelineTrace.registerThread("ffmpeg-in")
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val backlog = queue.backlogAfterCurrent()
                            stats.onQueue(System.nanoTime() - job.receivedNs, backlog.pendingFrames)
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recover("video backlog kept growing or exceeded hard limit")
                            } else if (referenceChain.accepts(job.nalus, VideoCodec.H264)) {
                                referenceChain.onQueued()
                                decode(job.nalus, job.receivedNs)
                            }
                        }
                        is VideoJob.SurfaceChanged -> renderer.setSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        is VideoJob.RefreshPicture -> {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                            repaintLastFrame()
                        }
                        VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    if (referenceChain.needsKeyFrame && lastConfig != null && renderer.currentSurface != null) {
                        requestKeyFrameIfDue()
                    }
                    stats.logIfDue()?.let { report(it); stats.takeCodecTrace()?.let(report) }
                } catch (error: Exception) {
                    if (running && failureReports < MAX_FAILURE_REPORTS) {
                        failureReports++
                        Log.e(TAG, "ffmpeg decoder job failed: ${job?.javaClass?.simpleName}", error)
                        report("ffmpeg decoder stage=${job?.javaClass?.simpleName} failed: $error")
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            renderer.release()
            queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
            runCatching { onExit(this) }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (handle != 0L && previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "ffmpeg decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        if (config.codec != VideoCodec.H264) {
            report("ffmpeg decoder refused codec=${config.codec}")
            return
        }
        handle = FfmpegNative.nativeOpen(threads)
        if (handle == 0L) {
            report("ffmpeg decoder open failed threads=$threads")
            return
        }
        // Feed the in-band parameter sets first; the stream's access units then decode at once.
        val (sps, pps) = MediaCodecSupport.avcParameterSets(config.codecData)
        if (sps.isNotEmpty()) FfmpegNative.nativeDecode(handle, START_CODE + sps, (START_CODE + sps).size, EMPTY, EMPTY, EMPTY, frameInfo)
        if (pps.isNotEmpty()) FfmpegNative.nativeDecode(handle, START_CODE + pps, (START_CODE + pps).size, EMPTY, EMPTY, EMPTY, frameInfo)
        report("decoder=ffmpeg-h264 threads=$threads size=${width}x$height")
    }

    private fun decode(nalus: ByteArray, receivedNs: Long) {
        if (handle == 0L) return
        val y = planeY
        val u = planeU
        val v = planeV
        if (y == null || u == null || v == null) {
            // Dimensions arrive with the first frame; probe once with empty planes.
            val probe = FfmpegNative.nativeDecode(handle, nalus, nalus.size, EMPTY, EMPTY, EMPTY, frameInfo)
            if (probe <= 0 || frameInfo[0] <= 0 || frameInfo[1] <= 0) return
            allocatePlanes(frameInfo[0], frameInfo[1])
            return
        }
        val start = System.nanoTime()
        val produced = FfmpegNative.nativeDecode(handle, nalus, nalus.size, y, u, v, frameInfo)
        if (produced <= 0) return
        val decodeNanos = System.nanoTime() - start
        stats.onDecodeLatency(decodeNanos)
        liveDecodeNanos.addAndGet(decodeNanos)
        liveDecodeSamples.incrementAndGet()
        lastFrameWidth = frameInfo[0]
        lastFrameHeight = frameInfo[1]
        lastColorspace = frameInfo[2]
        lastColorRange = frameInfo[3]
        if (renderer.draw(lastFrameWidth, lastFrameHeight, y, u, v, lastColorspace, lastColorRange)) {
            stats.onRendered()
            liveRendered.incrementAndGet()
        }
    }

    private fun allocatePlanes(w: Int, h: Int) {
        planeY = directBuffer(w * h)
        planeU = directBuffer(w * h / 4)
        planeV = directBuffer(w * h / 4)
        report("ffmpeg decoder frame size ${w}x$h")
    }

    private fun repaintLastFrame() {
        val y = planeY ?: return
        val u = planeU ?: return
        val v = planeV ?: return
        if (lastFrameWidth > 0 && renderer.draw(lastFrameWidth, lastFrameHeight, y, u, v, lastColorspace, lastColorRange)) {
            stats.onRendered()
        }
    }

    private fun detach(request: SurfaceDetachRequest) {
        val outcome = detachAction(renderer.currentSurface, request.surface, request.park, handle != 0L)
        // Both parked and released stop rendering; decoding keeps draining either way, so a frame
        // after the handoff cannot reach the surface that is going away.
        if (outcome != DetachOutcome.NOT_RENDERING) renderer.stopRendering()
        request.complete(outcome)
    }

    private fun recover(reason: String) {
        stats.onRecovery()
        if (handle != 0L) FfmpegNative.nativeFlush(handle)
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
        report("ffmpeg decoder recovered: $reason")
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (now - lastKeyFrameRequestNs < KEYFRAME_REQUEST_INTERVAL_NS) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun releaseDecoder() {
        if (handle != 0L) {
            FfmpegNative.nativeClose(handle)
            handle = 0L
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0)
        const val MAX_FAILURE_REPORTS = 5
        const val KEYFRAME_REQUEST_INTERVAL_NS = 500_000_000L

        fun directBuffer(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

/**
 * Draws planar YUV 4:2:0 frames onto a Surface through EGL/GLES2: three GL_LUMINANCE textures and
 * a YUV-to-RGB shader. All methods run on the decoder worker; the EGL surface is rebuilt whenever
 * the target Surface changes, because an EGLNativeWindowType cannot follow a Surface that was
 * destroyed. GL calls happen only after eglMakeCurrent: this head unit has no surfaceless context,
 * and compiling a shader before that returns failure with an empty info log.
 */
private class FfmpegGlRenderer(private val report: (String) -> Unit) {
    var currentSurface: Surface? = null
        private set
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var targetSurface: Surface? = null
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uRow0 = 0
    private var uRow1 = 0
    private var uRow2 = 0
    private var uOffset = 0
    private var textures = IntArray(3)
    private var textureWidth = IntArray(3)
    private var textureHeight = IntArray(3)
    private var quad: FloatBuffer? = null
    private var reportedGlFailure = false
    private var lastMatrix = FloatArray(9)
    private var lastOffset = FloatArray(3)

    fun setSurface(surface: Surface?) {
        targetSurface = surface
        if (surface == null || surface !== currentSurface) destroySurface()
    }

    fun stopRendering() {
        targetSurface = null
        destroySurface()
    }

    fun draw(w: Int, h: Int, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, colorspace: Int, range: Int): Boolean {
        val surface = targetSurface ?: return false
        if (surface !== currentSurface || eglSurface == EGL14.EGL_NO_SURFACE) {
            if (!buildSurface(surface)) return false
        }
        return try {
            upload(0, w, h, y)
            upload(1, w / 2, h / 2, u)
            upload(2, w / 2, h / 2, v)
            chooseConversion(colorspace, range)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("draw")
            // A swap that fails without throwing left the first build showing nothing and no clue.
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                throw IllegalStateException("eglSwapBuffers failed eglError=0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            true
        } catch (error: Exception) {
            handleGlFailure("draw", error)
            false
        }
    }

    private fun upload(index: Int, w: Int, h: Int, plane: ByteBuffer) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
        plane.position(0)
        plane.limit(plane.capacity())
        if (textureWidth[index] != w || textureHeight[index] != h) {
            textureWidth[index] = w
            textureHeight[index] = h
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, plane,
            )
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, plane,
            )
        }
    }

    private fun chooseConversion(colorspace: Int, range: Int) {
        // CarPlay's picture is full range (0-255); an explicit limited flag in the stream wins.
        val full = range != 1 // AVCOL_RANGE_MPEG
        val bt601 = colorspace == 5 || colorspace == 6 // BT470BG, SMPTE170M
        val (matrix, offset) = when {
            full && bt601 -> FULL_601
            full -> FULL_709
            bt601 -> LIMITED_601
            else -> LIMITED_709
        }
        if (!matrix.contentEquals(lastMatrix) || !offset.contentEquals(lastOffset)) {
            lastMatrix = matrix
            lastOffset = offset
            // Stored column-major; the shader dots each row so Vivante does not need a mat3 uniform.
            GLES20.glUniform3fv(uRow0, 1, floatArrayOf(matrix[0], matrix[3], matrix[6]), 0)
            GLES20.glUniform3fv(uRow1, 1, floatArrayOf(matrix[1], matrix[4], matrix[7]), 0)
            GLES20.glUniform3fv(uRow2, 1, floatArrayOf(matrix[2], matrix[5], matrix[8]), 0)
            GLES20.glUniform3fv(uOffset, 1, offset, 0)
        }
    }

    private fun buildSurface(surface: Surface): Boolean {
        return try {
            ensureContext()
            destroySurfaceKeepContext()
            eglSurface = EGL14.eglCreateWindowSurface(display, config!!, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed eglError=0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw IllegalStateException("eglMakeCurrent failed eglError=0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            currentSurface = surface
            if (program == 0) buildProgram()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            fitViewport()
            true
        } catch (error: Exception) {
            handleGlFailure("surface", error)
            false
        }
    }

    private fun ensureContext() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, IntArray(1), 0) && configs[0] != null) {
            "eglChooseConfig failed"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    private fun buildProgram() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val created = GLES20.glCreateProgram()
        GLES20.glAttachShader(created, vertex)
        GLES20.glAttachShader(created, fragment)
        GLES20.glLinkProgram(created)
        val status = IntArray(1)
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(created)
            GLES20.glDeleteProgram(created)
            throw IllegalStateException("program link failed: $info")
        }
        program = created
        // A new program has no uniforms yet; force the next frame to upload the conversion.
        lastMatrix = FloatArray(9)
        lastOffset = FloatArray(3)
        GLES20.glUseProgram(program)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uRow0 = GLES20.glGetUniformLocation(program, "uRow0")
        uRow1 = GLES20.glGetUniformLocation(program, "uRow1")
        uRow2 = GLES20.glGetUniformLocation(program, "uRow2")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        GLES20.glGenTextures(3, textures, 0)
        for (index in textures.indices) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "yTex"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "vTex"), 2)
        }
        // One quad covers the screen; the viewport letterboxes the frame's aspect ratio.
        val quadData = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(quadData.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(quadData); position(0) }
        quad?.let { buffer ->
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(aPosition)
            buffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(aTexCoord)
        }
    }

    /** Fills the surface, matching how SurfaceFlinger scales the codec's output onto it. */
    private fun fitViewport() {
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0], height[0])
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        // Old GPU drivers report failure with an empty info log; the source is the only clue.
        check(status[0] == GLES20.GL_TRUE) {
            val glError = GLES20.glGetError()
            "shader compile failed glError=0x${Integer.toHexString(glError)} " +
                "info=${GLES20.glGetShaderInfoLog(shader)} source:\n$source"
        }
        return shader
    }

    private fun checkGlError(what: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "glError $error at $what" }
    }

    private fun destroySurface() {
        destroySurfaceKeepContext()
    }

    private fun destroySurfaceKeepContext() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        currentSurface = null
    }

    fun release() {
        destroySurfaceKeepContext()
        if (context != EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            if (textures[0] != 0) runCatching { GLES20.glDeleteTextures(3, textures, 0) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            context = EGL14.EGL_NO_CONTEXT
            program = 0
            textures = IntArray(3)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglTerminate(display) }
            display = EGL14.EGL_NO_DISPLAY
        }
        config = null
    }

    private fun handleGlFailure(stage: String, error: Exception) {
        destroySurfaceKeepContext()
        if (!reportedGlFailure) {
            reportedGlFailure = true
            Log.e(TAG, "ffmpeg renderer $stage failed", error)
            report("ffmpeg renderer $stage failed: $error")
        }
    }

    private var config: EGLConfig? = null

    private companion object {
        const val TAG = "xcertplay-usb"

        // Column-major 3x3, yuv + offset into RGB; U and V arrive centred at 0.5.
        // Full-range BT.709 (CarPlay's picture), full-range BT.601, and the two limited-range forms.
        val FULL_709 = floatArrayOf(
            1f, 1f, 1f,
            0f, -0.182586f, 1.855633f,
            1.574800f, -0.466991f, 0f,
        ) to floatArrayOf(0f, -0.50196078f, -0.50196078f)
        val FULL_601 = floatArrayOf(
            1f, 1f, 1f,
            0f, -0.344136f, 1.772000f,
            1.402000f, -0.714136f, 0f,
        ) to floatArrayOf(0f, -0.50196078f, -0.50196078f)
        val LIMITED_709 = floatArrayOf(
            1.164384f, 1.164384f, 1.164384f,
            0f, -0.213249f, 2.112402f,
            1.792741f, -0.532907f, 0f,
        ) to floatArrayOf(-0.06274510f, -0.50196078f, -0.50196078f)
        val LIMITED_601 = floatArrayOf(
            1.164384f, 1.164384f, 1.164384f,
            0f, -0.391762f, 2.017232f,
            1.596027f, -0.812968f, 0f,
        ) to floatArrayOf(-0.06274510f, -0.50196078f, -0.50196078f)

        // Vivante on this i.MX6 rejects a blank first line, a missing trailing newline, and mat3.
        // precision is first, trimIndent drops the blank line, and the extra newline stays.
        val VERTEX_SHADER = """
            precision mediump float;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent() + "\n"

        val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D yTex;
            uniform sampler2D uTex;
            uniform sampler2D vTex;
            uniform vec3 uRow0;
            uniform vec3 uRow1;
            uniform vec3 uRow2;
            uniform vec3 uOffset;
            void main() {
                vec3 yuv = vec3(
                    texture2D(yTex, vTexCoord).x,
                    texture2D(uTex, vTexCoord).x,
                    texture2D(vTex, vTexCoord).x) + uOffset;
                gl_FragColor = vec4(dot(uRow0, yuv), dot(uRow1, yuv), dot(uRow2, yuv), 1.0);
            }
        """.trimIndent() + "\n"
    }
}
