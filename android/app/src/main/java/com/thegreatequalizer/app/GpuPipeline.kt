package com.thegreatequalizer.app

import android.content.Context
import android.opengl.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * GPU compute pipeline using GLES 3.1 compute shaders.
 *
 * Creates its own EGL context + offscreen surface (no GLSurfaceView needed).
 * Must be called from a single thread (the calling thread becomes the GL thread).
 *
 * Two-pass pipeline:
 *   Pass 1: ARGB pixels → OKLab L, a, b, C_rel, hue (7 SSBOs)
 *   Pass 2: Transfer LUTs + CDF → reconstructed ARGB pixels (8 SSBOs)
 */
class GpuPipeline {

    companion object {
        private const val TAG = "GpuPipeline"
        private const val WORKGROUP_SIZE = 256
    }

    // EGL handles
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // Compute shader programs
    private var pass1Program = 0
    private var pass2Program = 0

    // Pass 1 uniform locations
    private var pass1PixelCountLoc = 0

    // Pass 2 uniform locations
    private var pass2PixelCountLoc = 0
    private var pass2BlackLLoc = 0
    private var pass2WhiteLLoc = 0
    private var pass2BlackCLoc = 0
    private var pass2WhiteCLoc = 0
    private var pass2ShAngleLoc = 0
    private var pass2ShStrLoc = 0
    private var pass2MidAngleLoc = 0
    private var pass2MidStrLoc = 0
    private var pass2HiAngleLoc = 0
    private var pass2HiStrLoc = 0

    // Pass 1 SSBOs: binding 0=pixelInput, 1=outL, 2=outA, 3=outB, 4=outCrel, 5=outHue, 6=gamutLut
    private var pass1Ssbos = IntArray(7)

    // Pass 2 SSBOs: binding 0=inL, 1=inCrel, 2=inHue, 3=transferL, 4=transferC, 5=cdfValues, 6=gamutLut, 7=outPixels
    private var pass2Ssbos = IntArray(8)

    // Shared SSBOs (allocated once for max size, reused)
    private var gamutLutSsbo = 0

    private var maxPixelCount = 0
    private var initialized = false

    /**
     * Data class returned from pass 1 readback.
     */
    data class Pass1Result(
        val L: FloatArray,
        val cRel: FloatArray
    )

    /**
     * Initialize EGL context, compile shaders, create gamut LUT SSBO.
     * Must be called before any processing.
     */
    fun init(context: Context, gamutLut: FloatArray) {
        initEgl()
        pass1Program = loadComputeShader(context, "shaders/pass1_rgb_to_oklab.glsl")
        pass2Program = loadComputeShader(context, "shaders/pass2_cdf_to_srgb.glsl")

        // Get uniform locations
        pass1PixelCountLoc = GLES31.glGetUniformLocation(pass1Program, "uPixelCount")

        pass2PixelCountLoc = GLES31.glGetUniformLocation(pass2Program, "uPixelCount")
        pass2BlackLLoc = GLES31.glGetUniformLocation(pass2Program, "uBlackL")
        pass2WhiteLLoc = GLES31.glGetUniformLocation(pass2Program, "uWhiteL")
        pass2BlackCLoc = GLES31.glGetUniformLocation(pass2Program, "uBlackC")
        pass2WhiteCLoc = GLES31.glGetUniformLocation(pass2Program, "uWhiteC")
        pass2ShAngleLoc = GLES31.glGetUniformLocation(pass2Program, "uShAngle")
        pass2ShStrLoc = GLES31.glGetUniformLocation(pass2Program, "uShStr")
        pass2MidAngleLoc = GLES31.glGetUniformLocation(pass2Program, "uMidAngle")
        pass2MidStrLoc = GLES31.glGetUniformLocation(pass2Program, "uMidStr")
        pass2HiAngleLoc = GLES31.glGetUniformLocation(pass2Program, "uHiAngle")
        pass2HiStrLoc = GLES31.glGetUniformLocation(pass2Program, "uHiStr")

        // Create gamut LUT SSBO (shared between passes)
        val gamutBuf = allocateFloatBuffer(gamutLut)
        val ids = IntArray(1)
        GLES31.glGenBuffers(1, ids, 0)
        gamutLutSsbo = ids[0]
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gamutLutSsbo)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, gamutLut.size * 4, gamutBuf, GLES31.GL_STATIC_DRAW)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        initialized = true
        Log.i(TAG, "GPU pipeline initialized")
    }

    /**
     * Ensure per-pixel SSBOs are allocated for the given pixel count.
     * Reallocates if the current allocation is too small.
     */
    private fun ensureBuffers(pixelCount: Int) {
        if (pixelCount <= maxPixelCount) return

        // Delete old owned buffers if any
        if (maxPixelCount > 0) {
            // Pass 1 owns bindings 0-5 (6 is shared gamut LUT)
            val toDelete = IntArray(6) { pass1Ssbos[it] }
            GLES31.glDeleteBuffers(6, toDelete, 0)
            // Pass 2 owns only bindings 3,4,5,7 (0,1,2 shared with pass1; 6 is gamut LUT)
            val toDelete2 = intArrayOf(pass2Ssbos[3], pass2Ssbos[4], pass2Ssbos[5], pass2Ssbos[7])
            GLES31.glDeleteBuffers(4, toDelete2, 0)
        }

        maxPixelCount = pixelCount
        val floatBytes = pixelCount * 4
        val intBytes = pixelCount * 4

        // Pass 1: generate 6 buffers for bindings 0-5
        val pass1Ids = IntArray(6)
        GLES31.glGenBuffers(6, pass1Ids, 0)
        for (i in 0..5) pass1Ssbos[i] = pass1Ids[i]
        pass1Ssbos[6] = gamutLutSsbo  // shared

        // binding 0: pixel input (uint[pixelCount])
        allocateSsbo(pass1Ssbos[0], intBytes)
        // binding 1-5: outL, outA, outB, outCrel, outHue (float[pixelCount] each)
        for (i in 1..5) {
            allocateSsbo(pass1Ssbos[i], floatBytes)
        }

        // Pass 2: generate 4 buffers for bindings 3,4,5,7
        val pass2Ids = IntArray(4)
        GLES31.glGenBuffers(4, pass2Ids, 0)

        // Shared from pass 1
        pass2Ssbos[0] = pass1Ssbos[1]  // inL = outL from pass 1
        pass2Ssbos[1] = pass1Ssbos[4]  // inCrel = outCrel from pass 1
        pass2Ssbos[2] = pass1Ssbos[5]  // inHue = outHue from pass 1
        // Owned by pass 2
        pass2Ssbos[3] = pass2Ids[0]    // transferL (float[256])
        pass2Ssbos[4] = pass2Ids[1]    // transferC (float[256])
        pass2Ssbos[5] = pass2Ids[2]    // cdfValues (float[256])
        pass2Ssbos[6] = gamutLutSsbo   // shared
        pass2Ssbos[7] = pass2Ids[3]    // output pixels (uint[pixelCount])

        allocateSsbo(pass2Ssbos[3], 256 * 4)
        allocateSsbo(pass2Ssbos[4], 256 * 4)
        allocateSsbo(pass2Ssbos[5], 256 * 4)
        allocateSsbo(pass2Ssbos[7], intBytes)

        Log.i(TAG, "Allocated GPU buffers for $pixelCount pixels")
    }

    /**
     * Run pass 1: ARGB pixels → OKLab decomposition.
     * Returns L and C_rel arrays for CPU histogram computation.
     */
    fun processPass1(pixels: IntArray, pixelCount: Int): Pass1Result {
        check(initialized) { "GpuPipeline not initialized" }
        ensureBuffers(pixelCount)

        val numGroups = (pixelCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE

        // Upload pixel data
        val pixelBuf = allocateIntBuffer(pixels)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pass1Ssbos[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixelCount * 4, pixelBuf)

        // Bind all pass 1 SSBOs
        for (i in 0..6) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, pass1Ssbos[i])
        }

        // Dispatch pass 1
        GLES31.glUseProgram(pass1Program)
        GLES30.glUniform1ui(pass1PixelCountLoc, pixelCount)
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // Read back L (binding 1) and C_rel (binding 4)
        val L = readBackFloatBuffer(pass1Ssbos[1], pixelCount)
        val cRel = readBackFloatBuffer(pass1Ssbos[4], pixelCount)

        return Pass1Result(L, cRel)
    }

    /**
     * Run pass 1 without reading back results to CPU.
     * OKLab SSBOs remain populated for a subsequent pass 2 dispatch.
     * Used by tiled full-res export to avoid unnecessary readback overhead.
     */
    fun processPass1NoReadback(pixels: IntArray, pixelCount: Int) {
        check(initialized) { "GpuPipeline not initialized" }
        ensureBuffers(pixelCount)

        val numGroups = (pixelCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE

        // Upload pixel data
        val pixelBuf = allocateIntBuffer(pixels)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pass1Ssbos[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixelCount * 4, pixelBuf)

        // Bind all pass 1 SSBOs
        for (i in 0..6) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, pass1Ssbos[i])
        }

        // Dispatch pass 1
        GLES31.glUseProgram(pass1Program)
        GLES30.glUniform1ui(pass1PixelCountLoc, pixelCount)
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /**
     * Run pass 2: CDF transfer + reconstruct + gamut clamp → ARGB pixels.
     */
    fun processPass2(
        transferL: FloatArray,
        transferC: FloatArray,
        cdfValues: FloatArray,
        blackL: Float,
        whiteL: Float,
        blackC: Float,
        whiteC: Float,
        chromaParams: FloatArray,  // [shAngle, shStr, midAngle, midStr, hiAngle, hiStr]
        pixelCount: Int
    ): IntArray {
        check(initialized) { "GpuPipeline not initialized" }

        val numGroups = (pixelCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE

        // Upload transfer LUTs and CDF values
        uploadFloatBuffer(pass2Ssbos[3], transferL)
        uploadFloatBuffer(pass2Ssbos[4], transferC)
        uploadFloatBuffer(pass2Ssbos[5], cdfValues)

        // Bind all pass 2 SSBOs
        for (i in 0..7) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, pass2Ssbos[i])
        }

        // Set uniforms
        GLES31.glUseProgram(pass2Program)
        GLES30.glUniform1ui(pass2PixelCountLoc, pixelCount)
        GLES20.glUniform1f(pass2BlackLLoc, blackL)
        GLES20.glUniform1f(pass2WhiteLLoc, whiteL)
        GLES20.glUniform1f(pass2BlackCLoc, blackC)
        GLES20.glUniform1f(pass2WhiteCLoc, whiteC)
        GLES20.glUniform1f(pass2ShAngleLoc, chromaParams[0])
        GLES20.glUniform1f(pass2ShStrLoc, chromaParams[1])
        GLES20.glUniform1f(pass2MidAngleLoc, chromaParams[2])
        GLES20.glUniform1f(pass2MidStrLoc, chromaParams[3])
        GLES20.glUniform1f(pass2HiAngleLoc, chromaParams[4])
        GLES20.glUniform1f(pass2HiStrLoc, chromaParams[5])

        // Dispatch pass 2
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // Read back output pixels
        return readBackIntBuffer(pass2Ssbos[7], pixelCount)
    }

    /**
     * Release all GPU resources.
     */
    fun release() {
        if (!initialized) return

        GLES31.glDeleteProgram(pass1Program)
        GLES31.glDeleteProgram(pass2Program)

        // Delete per-pixel buffers (skip shared gamut LUT references)
        val toDelete = mutableListOf<Int>()
        // Pass 1 owned: bindings 0-5 (not 6 which is gamutLut)
        for (i in 0..5) {
            if (pass1Ssbos[i] != 0) toDelete.add(pass1Ssbos[i])
        }
        // Pass 2 owned: bindings 3,4,5,7 (0,1,2 shared with pass1; 6 is gamutLut)
        for (i in intArrayOf(3, 4, 5, 7)) {
            if (pass2Ssbos[i] != 0 && pass2Ssbos[i] !in toDelete) toDelete.add(pass2Ssbos[i])
        }
        // Gamut LUT
        toDelete.add(gamutLutSsbo)

        if (toDelete.isNotEmpty()) {
            val arr = toDelete.toIntArray()
            GLES31.glDeleteBuffers(arr.size, arr, 0)
        }

        destroyEgl()
        initialized = false
        maxPixelCount = 0
        Log.i(TAG, "GPU pipeline released")
    }

    // ---------------------------------------------------------------
    // EGL setup
    // ---------------------------------------------------------------

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        // Request OpenGL ES 3.1 context
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }
        if (numConfigs[0] == 0) {
            throw RuntimeException("No EGL config available")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        // Create 1x1 pbuffer surface
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

        Log.i(TAG, "EGL initialized: GL_VERSION=${GLES31.glGetString(GLES31.GL_VERSION)}")
    }

    private fun destroyEgl() {
        eglDisplay?.let { display ->
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(display, it) }
            eglContext?.let { EGL14.eglDestroyContext(display, it) }
            EGL14.eglTerminate(display)
        }
        eglDisplay = null
        eglContext = null
        eglSurface = null
    }

    // ---------------------------------------------------------------
    // Shader compilation
    // ---------------------------------------------------------------

    private fun loadComputeShader(context: Context, assetPath: String): Int {
        val source = context.assets.open(assetPath).bufferedReader().readText()

        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            throw RuntimeException("Compile error in $assetPath:\n$log")
        }

        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)

        val linked = IntArray(1)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            throw RuntimeException("Link error in $assetPath:\n$log")
        }

        GLES31.glDeleteShader(shader)
        Log.i(TAG, "Compiled compute shader: $assetPath")
        return program
    }

    // ---------------------------------------------------------------
    // Buffer helpers
    // ---------------------------------------------------------------

    private fun allocateSsbo(id: Int, sizeBytes: Int) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, sizeBytes, null, GLES31.GL_DYNAMIC_COPY)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun uploadFloatBuffer(ssboId: Int, data: FloatArray) {
        val buf = allocateFloatBuffer(data)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, data.size * 4, buf)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun readBackFloatBuffer(ssboId: Int, count: Int): FloatArray {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 4, GLES31.GL_MAP_READ_BIT
        ) as ByteBuffer
        val result = FloatArray(count)
        mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(result)
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return result
    }

    private fun readBackIntBuffer(ssboId: Int, count: Int): IntArray {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 4, GLES31.GL_MAP_READ_BIT
        ) as ByteBuffer
        val result = IntArray(count)
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(result)
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return result
    }

    private fun allocateFloatBuffer(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data)
        buf.position(0)
        return buf
    }

    private fun allocateIntBuffer(data: IntArray): IntBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        buf.put(data)
        buf.position(0)
        return buf
    }
}
