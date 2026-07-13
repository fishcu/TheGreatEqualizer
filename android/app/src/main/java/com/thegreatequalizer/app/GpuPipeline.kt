package com.thegreatequalizer.app

import android.content.Context
import android.opengl.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.sqrt

/**
 * GPU compute pipeline using GLES 3.1 compute shaders.
 *
 * Creates its own EGL context + offscreen surface (no GLSurfaceView needed).
 * Must be called from a single thread (the calling thread becomes the GL thread).
 *
 * Two-pass pipeline:
 *   Pass 1: ARGB pixels → OKLab L, C_rel, hue
 *   Pass 2: Transfer LUTs + CDF → reconstructed ARGB pixels
 *
 * Both passes use the four SSBOs guaranteed by the GLES 3.1 specification.
 */
class GpuPipeline {

    companion object {
        private const val TAG = "GpuPipeline"
        private const val WORKGROUP_SIZE = 128
        private const val REQUIRED_SSBO_BLOCKS = 4
        private const val NUM_BINS = 256
        private const val GAMUT_FLOAT_COUNT = 256 * 360
        private const val TRANSFER_L_OFFSET = GAMUT_FLOAT_COUNT
        private const val TRANSFER_C_OFFSET = TRANSFER_L_OFFSET + NUM_BINS
        private const val CDF_OFFSET = TRANSFER_C_OFFSET + NUM_BINS
        private const val LOOKUP_FLOAT_COUNT = CDF_OFFSET + NUM_BINS
        private const val GRAIN_PRIMARY_SIZE = 512
        private const val GRAIN_SECONDARY_SIZE = 509
        private const val GRAIN_PRIMARY_TEXTURE_UNIT = 0
        private const val GRAIN_SECONDARY_TEXTURE_UNIT = 1
        private const val REQUIRED_COMPUTE_TEXTURE_UNITS = 2
        private const val GRAIN_REFERENCE_SIZE = 1.25f
        private const val GRAIN_OFFSET_Y_SALT = 0x9e3779b9u
        private const val PIXEL_BINDING = 0
        private const val ANALYSIS_BINDING = 1
        private const val HUE_BINDING = 2
        private const val LOOKUP_BINDING = 3
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
    private var pass2GrainPrimaryLoc = 0
    private var pass2GrainSecondaryLoc = 0
    private var pass2GrainAmountLoc = 0
    private var pass2GrainSizeLoc = 0
    private var pass2GrainPatternOffsetLoc = 0
    private var pass2ImageWidthLoc = 0
    private var pass2ImageOriginLoc = 0

    // binding 0=pixel input/output, 1=interleaved L/C_rel, 2=hue, 3=all lookup data
    private val ssbos = IntArray(REQUIRED_SSBO_BLOCKS)
    private val grainTextures = IntArray(REQUIRED_COMPUTE_TEXTURE_UNITS)
    private var maxPixelCount = 0
    private var maxShaderStorageBlockSize = 0L
    private var initialized = false

    /**
     * Data class returned from pass 1 readback.
     */
    data class Pass1Result(
        val L: FloatArray,
        val cRel: FloatArray
    )

    data class GrainRenderParams(
        val amount: Float,
        val size: Float,
        val rowWidth: Int,
        val originX: Int,
        val originY: Int
    )

    /**
     * Initialize EGL, validate the required GLES 3.1 capabilities, compile
     * shaders, and create the shared lookup-data SSBO.
     * Must be called before any processing.
     */
    fun init(context: Context, gamutLut: FloatArray) {
        check(gamutLut.size == GAMUT_FLOAT_COUNT) {
            "Expected $GAMUT_FLOAT_COUNT gamut values, received ${gamutLut.size}"
        }

        try {
            initEgl()
            validateCapabilities()
            pass1Program = loadComputeShader(context, "shaders/pass1_rgb_to_oklab.glsl")
            pass2Program = loadComputeShader(context, "shaders/pass2_cdf_to_srgb.glsl")

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
            pass2GrainPrimaryLoc = GLES31.glGetUniformLocation(pass2Program, "uGrainPrimary")
            pass2GrainSecondaryLoc = GLES31.glGetUniformLocation(pass2Program, "uGrainSecondary")
            pass2GrainAmountLoc = GLES31.glGetUniformLocation(pass2Program, "uGrainAmount")
            pass2GrainSizeLoc = GLES31.glGetUniformLocation(pass2Program, "uGrainSize")
            pass2GrainPatternOffsetLoc =
                GLES31.glGetUniformLocation(pass2Program, "uGrainPatternOffset")
            pass2ImageWidthLoc = GLES31.glGetUniformLocation(pass2Program, "uImageWidth")
            pass2ImageOriginLoc = GLES31.glGetUniformLocation(pass2Program, "uImageOrigin")

            GLES31.glGenBuffers(1, ssbos, LOOKUP_BINDING)
            allocateSsbo(ssbos[LOOKUP_BINDING], LOOKUP_FLOAT_COUNT * 4)
            uploadFloatBuffer(ssbos[LOOKUP_BINDING], 0, gamutLut)

            GLES31.glGenTextures(grainTextures.size, grainTextures, 0)
            uploadGrainTexture(
                context,
                grainTextures[GRAIN_PRIMARY_TEXTURE_UNIT],
                "grain/grain_primary_r8.bin",
                GRAIN_PRIMARY_SIZE
            )
            uploadGrainTexture(
                context,
                grainTextures[GRAIN_SECONDARY_TEXTURE_UNIT],
                "grain/grain_secondary_r8.bin",
                GRAIN_SECONDARY_SIZE
            )
            checkGlError("lookup buffer initialization")

            initialized = true
            Log.i(TAG, "GPU pipeline initialized")
        } catch (error: Exception) {
            releaseResources()
            throw error
        }
    }

    /**
     * Ensure per-pixel SSBOs are allocated for the given pixel count.
     * Reallocates if the current allocation is too small.
     */
    private fun ensureBuffers(pixelCount: Int) {
        if (pixelCount <= maxPixelCount) return

        val pixelBytes = pixelCount * 4
        val analysisBytes = pixelCount * 2 * 4
        val hueBytes = pixelCount * 4
        requireBlockSize("pixel", pixelBytes)
        requireBlockSize("analysis", analysisBytes)
        requireBlockSize("hue", hueBytes)

        if (maxPixelCount > 0) {
            GLES31.glDeleteBuffers(3, ssbos, PIXEL_BINDING)
        }

        GLES31.glGenBuffers(3, ssbos, PIXEL_BINDING)
        allocateSsbo(ssbos[PIXEL_BINDING], pixelBytes)
        allocateSsbo(ssbos[ANALYSIS_BINDING], analysisBytes)
        allocateSsbo(ssbos[HUE_BINDING], hueBytes)
        checkGlError("per-pixel buffer allocation")

        maxPixelCount = pixelCount
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
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbos[PIXEL_BINDING])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixelCount * 4, pixelBuf)

        for (binding in ssbos.indices) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, ssbos[binding])
        }

        GLES31.glUseProgram(pass1Program)
        GLES30.glUniform1ui(pass1PixelCountLoc, pixelCount)
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
        )
        checkGlError("pass 1 dispatch")

        return readBackAnalysis(pixelCount)
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
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbos[PIXEL_BINDING])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixelCount * 4, pixelBuf)

        for (binding in ssbos.indices) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, ssbos[binding])
        }

        GLES31.glUseProgram(pass1Program)
        GLES30.glUniform1ui(pass1PixelCountLoc, pixelCount)
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        checkGlError("pass 1 dispatch")
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
        grainParams: GrainRenderParams,
        pixelCount: Int
    ): IntArray {
        check(initialized) { "GpuPipeline not initialized" }
        require(grainParams.amount in 0.0f..0.15f) {
            "Grain amount must be in [0.0, 0.15]"
        }
        require(grainParams.size in 0.25f..4.0f) {
            "Grain size must be in [0.25, 4.0]"
        }
        require(grainParams.rowWidth > 0 && pixelCount % grainParams.rowWidth == 0) {
            "Pixel count must contain complete rows"
        }
        require(grainParams.originX >= 0 && grainParams.originY >= 0) {
            "Grain origin must be non-negative"
        }

        val numGroups = (pixelCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE

        uploadFloatBuffer(ssbos[LOOKUP_BINDING], TRANSFER_L_OFFSET * 4, transferL)
        uploadFloatBuffer(ssbos[LOOKUP_BINDING], TRANSFER_C_OFFSET * 4, transferC)
        uploadFloatBuffer(ssbos[LOOKUP_BINDING], CDF_OFFSET * 4, cdfValues)

        for (binding in ssbos.indices) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, ssbos[binding])
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
        val grainGain = sqrt(GRAIN_REFERENCE_SIZE / grainParams.size)
        GLES20.glUniform1f(pass2GrainAmountLoc, grainParams.amount * grainGain)
        GLES20.glUniform1f(pass2GrainSizeLoc, grainParams.size)
        val sizeBits = grainParams.size.toRawBits().toUInt()
        GLES20.glUniform2f(
            pass2GrainPatternOffsetLoc,
            grainOffset(sizeBits),
            grainOffset(sizeBits xor GRAIN_OFFSET_Y_SALT)
        )
        GLES20.glUniform1i(pass2ImageWidthLoc, grainParams.rowWidth)
        GLES20.glUniform2i(
            pass2ImageOriginLoc,
            grainParams.originX,
            grainParams.originY
        )

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + GRAIN_PRIMARY_TEXTURE_UNIT)
        GLES31.glBindTexture(
            GLES31.GL_TEXTURE_2D,
            grainTextures[GRAIN_PRIMARY_TEXTURE_UNIT]
        )
        GLES20.glUniform1i(pass2GrainPrimaryLoc, GRAIN_PRIMARY_TEXTURE_UNIT)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + GRAIN_SECONDARY_TEXTURE_UNIT)
        GLES31.glBindTexture(
            GLES31.GL_TEXTURE_2D,
            grainTextures[GRAIN_SECONDARY_TEXTURE_UNIT]
        )
        GLES20.glUniform1i(pass2GrainSecondaryLoc, GRAIN_SECONDARY_TEXTURE_UNIT)

        // Dispatch pass 2
        GLES31.glDispatchCompute(numGroups, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
        )
        checkGlError("pass 2 dispatch")

        // Read back output pixels
        return readBackIntBuffer(ssbos[PIXEL_BINDING], pixelCount)
    }

    /**
     * Release all GPU resources.
     */
    fun release() {
        releaseResources()
        initialized = false
        maxPixelCount = 0
        Log.i(TAG, "GPU pipeline released")
    }

    private fun releaseResources() {
        if (pass1Program != 0) {
            GLES31.glDeleteProgram(pass1Program)
            pass1Program = 0
        }
        if (pass2Program != 0) {
            GLES31.glDeleteProgram(pass2Program)
            pass2Program = 0
        }
        if (grainTextures.any { it != 0 }) {
            GLES31.glDeleteTextures(grainTextures.size, grainTextures, 0)
            grainTextures.fill(0)
        }

        val allocatedSsbos = ssbos.filter { it != 0 }.toIntArray()
        if (allocatedSsbos.isNotEmpty()) {
            GLES31.glDeleteBuffers(allocatedSsbos.size, allocatedSsbos, 0)
            ssbos.fill(0)
        }

        destroyEgl()
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

        Log.i(TAG, "EGL initialized")
    }

    private fun validateCapabilities() {
        val major = IntArray(1)
        val minor = IntArray(1)
        val maxInvocations = IntArray(1)
        val maxWorkGroupSizeX = IntArray(1)
        val maxStorageBlocks = IntArray(1)
        val maxStorageBindings = IntArray(1)
        val maxComputeTextureUnits = IntArray(1)
        val maxBlockSize = LongArray(1)

        GLES31.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
        GLES31.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
        GLES31.glGetIntegerv(
            GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS,
            maxInvocations,
            0
        )
        GLES31.glGetIntegeri_v(
            GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE,
            0,
            maxWorkGroupSizeX,
            0
        )
        GLES31.glGetIntegerv(
            GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,
            maxStorageBlocks,
            0
        )
        GLES31.glGetIntegerv(
            GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,
            maxStorageBindings,
            0
        )
        GLES31.glGetIntegerv(
            GLES31.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS,
            maxComputeTextureUnits,
            0
        )
        GLES30.glGetInteger64v(
            GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE,
            maxBlockSize,
            0
        )
        checkGlError("GPU capability query")

        val version = GLES31.glGetString(GLES31.GL_VERSION) ?: "unknown"
        val renderer = GLES31.glGetString(GLES31.GL_RENDERER) ?: "unknown"
        val vendor = GLES31.glGetString(GLES31.GL_VENDOR) ?: "unknown"
        val details = "version=$version, renderer=$renderer, vendor=$vendor, " +
            "workgroupInvocations=${maxInvocations[0]}, workgroupSizeX=${maxWorkGroupSizeX[0]}, " +
            "computeStorageBlocks=${maxStorageBlocks[0]}, storageBindings=${maxStorageBindings[0]}, " +
            "computeTextureUnits=${maxComputeTextureUnits[0]}, " +
            "maxStorageBlockBytes=${maxBlockSize[0]}"
        Log.i(TAG, "GPU capabilities: $details")

        if (major[0] < 3 || (major[0] == 3 && minor[0] < 1)) {
            throw UnsupportedOperationException("OpenGL ES 3.1 is required; $details")
        }
        if (maxInvocations[0] < WORKGROUP_SIZE || maxWorkGroupSizeX[0] < WORKGROUP_SIZE) {
            throw UnsupportedOperationException(
                "Compute workgroups of $WORKGROUP_SIZE invocations are required; $details"
            )
        }
        if (maxStorageBlocks[0] < REQUIRED_SSBO_BLOCKS ||
            maxStorageBindings[0] < REQUIRED_SSBO_BLOCKS) {
            throw UnsupportedOperationException(
                "$REQUIRED_SSBO_BLOCKS compute shader-storage blocks are required; $details"
            )
        }
        if (maxComputeTextureUnits[0] < REQUIRED_COMPUTE_TEXTURE_UNITS) {
            throw UnsupportedOperationException(
                "$REQUIRED_COMPUTE_TEXTURE_UNITS compute texture units are required; $details"
            )
        }
        if (maxBlockSize[0] < LOOKUP_FLOAT_COUNT * 4L) {
            throw UnsupportedOperationException(
                "Shader-storage blocks of at least ${LOOKUP_FLOAT_COUNT * 4L} bytes are required; $details"
            )
        }

        maxShaderStorageBlockSize = maxBlockSize[0]
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

    private fun grainOffset(value: UInt): Float {
        var hash = value
        hash = hash xor (hash shr 16)
        hash *= 0x7feb352du
        hash = hash xor (hash shr 15)
        hash *= 0x846ca68bu
        hash = hash xor (hash shr 16)
        return (hash and 0xffffu).toFloat()
    }

    private fun uploadGrainTexture(
        context: Context,
        textureId: Int,
        assetPath: String,
        size: Int
    ) {
        val encodedGrain = context.assets.open(assetPath).use { it.readBytes() }
        check(encodedGrain.size == size * size) {
            "$assetPath must contain exactly ${size * size} bytes"
        }
        val pixels = ByteBuffer.allocateDirect(encodedGrain.size)
            .order(ByteOrder.nativeOrder())
        pixels.put(encodedGrain)
        pixels.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureId)
        GLES31.glTexParameteri(
            GLES31.GL_TEXTURE_2D,
            GLES31.GL_TEXTURE_MIN_FILTER,
            GLES31.GL_LINEAR
        )
        GLES31.glTexParameteri(
            GLES31.GL_TEXTURE_2D,
            GLES31.GL_TEXTURE_MAG_FILTER,
            GLES31.GL_LINEAR
        )
        GLES31.glTexParameteri(
            GLES31.GL_TEXTURE_2D,
            GLES31.GL_TEXTURE_WRAP_S,
            GLES31.GL_REPEAT
        )
        GLES31.glTexParameteri(
            GLES31.GL_TEXTURE_2D,
            GLES31.GL_TEXTURE_WRAP_T,
            GLES31.GL_REPEAT
        )
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES31.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            size,
            size,
            0,
            GLES30.GL_RED,
            GLES31.GL_UNSIGNED_BYTE,
            pixels
        )
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
        checkGlError("grain texture upload: $assetPath")
    }

    private fun uploadFloatBuffer(ssboId: Int, offsetBytes: Int, data: FloatArray) {
        val buf = allocateFloatBuffer(data)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        GLES31.glBufferSubData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            offsetBytes,
            data.size * 4,
            buf
        )
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun readBackAnalysis(pixelCount: Int): Pass1Result {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbos[ANALYSIS_BINDING])
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            0,
            pixelCount * 2 * 4,
            GLES31.GL_MAP_READ_BIT
        ) as ByteBuffer
        val values = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val lValues = FloatArray(pixelCount)
        val cRelValues = FloatArray(pixelCount)
        for (index in 0 until pixelCount) {
            lValues[index] = values[index * 2]
            cRelValues[index] = values[index * 2 + 1]
        }
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return Pass1Result(lValues, cRelValues)
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

    private fun requireBlockSize(name: String, sizeBytes: Int) {
        check(sizeBytes.toLong() <= maxShaderStorageBlockSize) {
            "$name buffer requires $sizeBytes bytes, but the GPU supports " +
                "$maxShaderStorageBlockSize bytes per shader-storage block"
        }
    }

    private fun checkGlError(operation: String) {
        val error = GLES31.glGetError()
        check(error == GLES31.GL_NO_ERROR) {
            "$operation failed with OpenGL error 0x${error.toString(16)}"
        }
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
