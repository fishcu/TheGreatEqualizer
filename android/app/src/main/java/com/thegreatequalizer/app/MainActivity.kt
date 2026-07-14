package com.thegreatequalizer.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator

import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private data class EditEntry(
        val label: String,
        val before: PipelineParams,
        val after: PipelineParams,
        val navigationItemId: Int
    )

    private data class ActiveEdit(
        val label: String,
        val before: PipelineParams,
        val navigationItemId: Int
    )

    private data class OverlaySize(
        val width: Int,
        val height: Int
    )

    private enum class GpuStatus {
        INITIALIZING,
        READY,
        UNAVAILABLE
    }

    companion object {
        private const val TAG = "TheGreatEqualizer"
        private const val AB_DELAY_MS = 100L
        private const val HIRES_ZOOM_THRESHOLD = 1.0f
        private const val HIRES_DEBOUNCE_MS = 150L
        private const val HIRES_PADDING = 0.2f  // 20% extra on each side
        private const val MAX_HIRES_OVERLAY_PIXELS = 2_000_000
        private const val MAX_EDIT_HISTORY = 100
        private const val ENABLED_HISTORY_ALPHA = 0.9f
        private const val DISABLED_HISTORY_ALPHA = 0.3f
    }

    private lateinit var imageView: ZoomableImageView
    private lateinit var addPhotoOverlay: View
    private lateinit var fabLoad: FloatingActionButton
    private lateinit var fabExport: FloatingActionButton
    private lateinit var fabShare: FloatingActionButton
    private lateinit var fabRandomize: FloatingActionButton
    private lateinit var editHistoryControls: View
    private lateinit var fabUndo: FloatingActionButton
    private lateinit var fabRedo: FloatingActionButton
    private lateinit var controlsPanel: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var presetRepository: PresetRepository
    private var currentNavItemId: Int = R.id.nav_light
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private lateinit var gamutLut: FloatArray
    private lateinit var gpuPipeline: GpuPipeline
    private var gpuStatus = GpuStatus.INITIALIZING
    private var gpuFailureDetail = ""
    private var gpuErrorDialog: AlertDialog? = null
    private val gpuDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gpu-pipeline").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
    // Staged pipeline state
    var pipelineState: PipelineState = PipelineState()
        private set
    var pipelineParams: PipelineParams = PipelineParams()
        private set
    private val undoHistory = ArrayDeque<EditEntry>()
    private val redoHistory = ArrayDeque<EditEntry>()
    private var activeEdit: ActiveEdit? = null
    private var isRendering = false
    private var renderJob: Job? = null
    private var pendingParams: PipelineParams? = null
    private var renderGeneration = 0L
    private var imageGeneration = 0L
    private var imageProcessingJob: Job? = null
    private var isShowingOriginal = false
    private var multiTouchActive = false
    private var abDelayJob: Job? = null
    var selectedPresetName: String? = null

    private lateinit var shakeDetector: ShakeDetector

    // Hi-res crop overlay state
    private var hiResCropJob: Job? = null
    private var hiResCropGeneration = 0L
    private var currentHiResBitmap: Bitmap? = null
    private var currentOriginalHiResBitmap: Bitmap? = null
    private var currentHiResCropRect: RectF? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { loadImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        presetRepository = PresetRepository(this)
        bindViews()

        shakeDetector = ShakeDetector(this) { runOnUiThread { randomizeParams("Shake randomize") } }

        // Set initial fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, LightFragment())
                .commit()
        }

        initializeGpuPipeline()

        handleImageIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImageIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(R.layout.activity_main)
        bindViews()

        // Restore image display
        if (processedBitmap != null) {
            showImageLoaded()
            imageView.setImageBitmap(processedBitmap)
        }

        // Restore bottom nav selection — triggers fragment re-attach via listener
        bottomNav.selectedItemId = currentNavItemId
    }

    private fun bindViews() {
        imageView = findViewById(R.id.imageView)
        addPhotoOverlay = findViewById(R.id.addPhotoOverlay)
        fabLoad = findViewById(R.id.fabLoad)
        fabExport = findViewById(R.id.fabExport)
        fabShare = findViewById(R.id.fabShare)
        fabRandomize = findViewById(R.id.fabRandomize)
        editHistoryControls = findViewById(R.id.editHistoryControls)
        fabUndo = findViewById(R.id.fabUndo)
        fabRedo = findViewById(R.id.fabRedo)
        controlsPanel = findViewById(R.id.controlsPanel)
        bottomNav = findViewById(R.id.bottomNav)

        addPhotoOverlay.setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

        // FAB actions
        fabLoad.setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        fabExport.setOnClickListener { exportImage() }
        fabShare.setOnClickListener { shareImage() }
        fabRandomize.setOnClickListener { randomizeParams("Randomize") }
        fabUndo.setOnClickListener { undoLastEdit() }
        fabRedo.setOnClickListener { redoLastEdit() }
        updateHistoryButtons()

        // Bottom navigation to swap fragments
        bottomNav.setOnItemSelectedListener { item ->
            currentNavItemId = item.itemId
            val fragment = when (item.itemId) {
                R.id.nav_light -> LightFragment()
                R.id.nav_color -> ColorFragment()
                R.id.nav_zoned_tint -> ZonedTintFragment()
                R.id.nav_fx -> FxFragment()
                R.id.nav_presets -> PresetsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
        }

        // A/B compare: instant show on single-finger press, restore on release.
        // Touch handling is in dispatchTouchEvent() below.

        // Viewport change listener for hi-res crop overlay
        imageView.onViewportChanged = { onViewportChanged() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isTouchOnImageView(event) && !isTouchOnImageAction(event)) {
                    val orig = pipelineState.originalBitmap
                    val proc = processedBitmap
                    if (orig != null && proc != null) {
                        multiTouchActive = false
                        // Delay A/B by 200ms to avoid flicker when second finger arrives
                        abDelayJob?.cancel()
                        abDelayJob = lifecycleScope.launch {
                            delay(AB_DELAY_MS)
                            if (!multiTouchActive) {
                                isShowingOriginal = true
                                imageView.clearOverlay()
                                updateImageBitmap(orig)
                                showCachedHiResOverlay(currentOriginalHiResBitmap)
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger arrived — cancel pending A/B, restore if already showing
                abDelayJob?.cancel()
                abDelayJob = null
                multiTouchActive = true
                if (isShowingOriginal) {
                    isShowingOriginal = false
                    val bmp = processedBitmap
                    if (bmp != null) updateImageBitmap(bmp)
                    showCachedHiResOverlay(currentHiResBitmap)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                abDelayJob?.cancel()
                abDelayJob = null
                if (isShowingOriginal) {
                    isShowingOriginal = false
                    val bmp = processedBitmap
                    if (bmp != null) updateImageBitmap(bmp)
                    showCachedHiResOverlay(currentHiResBitmap)
                }
                multiTouchActive = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchOnImageView(event: MotionEvent): Boolean {
        return isTouchInside(imageView, event)
    }

    private fun isTouchOnImageAction(event: MotionEvent): Boolean {
        return listOf(fabLoad, fabExport, fabShare, fabRandomize, fabUndo, fabRedo)
            .any { isTouchInside(it, event) }
    }

    private fun isTouchInside(view: View, event: MotionEvent): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX
        val y = event.rawY
        return x >= loc[0] && x <= loc[0] + view.width &&
               y >= loc[1] && y <= loc[1] + view.height
    }

    private fun showImageLoaded() {
        addPhotoOverlay.visibility = View.GONE
        editHistoryControls.visibility = View.VISIBLE
        updateHistoryButtons()
    }

    private fun handleImageIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") != true) return
                sharedImageUri(intent)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> return
        }

        if (uri == null) {
            Toast.makeText(this, "No image was included", Toast.LENGTH_SHORT).show()
            return
        }

        loadImageFromUri(uri)
    }

    @Suppress("DEPRECATION")
    private fun sharedImageUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            val bitmap = loadBitmapFromUri(uri)

            if (bitmap != null) {
                clearEditHistory()
                imageGeneration++
                imageProcessingJob?.cancel()
                renderJob?.cancel()
                renderJob = null
                isRendering = false
                invalidateHiResCrop()
                originalBitmap = bitmap
                processedBitmap = null
                pipelineState = PipelineState()
                pipelineParams = PipelineParams()
                pendingParams = null
                renderGeneration++
                imageView.setImageBitmap(null)
                showImageLoaded()
                processImage()
            } else {
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun processImage() {
        val src = originalBitmap ?: return
        when (gpuStatus) {
            GpuStatus.INITIALIZING -> return
            GpuStatus.UNAVAILABLE -> {
                showGpuUnavailableDialog()
                return
            }
            GpuStatus.READY -> Unit
        }

        val generation = imageGeneration
        val lut = gamutLut
        val gpu = gpuPipeline
        val initialParams = pipelineParams
        imageProcessingJob?.cancel()
        imageProcessingJob = lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                val state = withContext(gpuDispatcher) {
                    ImagePipeline.processPass1Only(src, lut, gpu, initialParams)
                }
                if (generation != imageGeneration) return@launch
                imageView.setImageBitmap(state.originalBitmap)

                var params = initialParams
                params = withContext(Dispatchers.Default) {
                    ImagePipeline.fitToInput(state, params, "light")
                }
                params = withContext(Dispatchers.Default) {
                    ImagePipeline.fitToInput(state, params, "color")
                }
                if (generation != imageGeneration) return@launch
                state.fittedParams = params

                val outBitmap = withContext(gpuDispatcher) {
                    ImagePipeline.processFromParams(state, params, lut, gpu)
                }
                if (generation != imageGeneration) return@launch

                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "Staged GPU processing took ${elapsed}ms")

                pipelineState = state
                pipelineParams = params
                processedBitmap = outBitmap
                state.processedBitmap = outBitmap
                imageView.setImageBitmap(outBitmap)
                notifyFragmentUpdate()
                updateHistoryButtons()
                maybeStartHiResCrop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Processing error", error)
                Toast.makeText(
                    this@MainActivity,
                    "Processing error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun notifyFragmentUpdate() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        when (currentFragment) {
            is LightFragment -> currentFragment.updateFromParams()
            is ColorFragment -> currentFragment.updateFromParams()
            is ZonedTintFragment -> currentFragment.updateFromParams()
            is FxFragment -> currentFragment.updateFromParams()
            is PresetsFragment -> currentFragment.refreshState()
        }
    }

    val presets: List<Preset>
        get() = presetRepository.presets

    fun findPreset(name: String): Preset? =
        presetRepository.findByName(name)

    fun savePreset(preset: Preset) {
        presetRepository.save(preset)
    }

    fun deletePreset(name: String) {
        presetRepository.delete(name)
    }

    fun movePreset(fromIndex: Int, toIndex: Int) {
        presetRepository.move(fromIndex, toIndex)
    }

    fun isImageLoaded(): Boolean = pipelineState.isImageLoaded()

    fun presetBaseline(): PipelineParams =
        pipelineState.fittedParams
            ?: error("Preset baseline requires a loaded image")

    fun applyPreset(name: String) {
        check(pipelineState.isImageLoaded()) {
            "Applying a preset requires a loaded image"
        }
        val preset = presetRepository.findByName(name)
            ?: error("Unknown preset: $name")
        val merged = PresetSettingCatalog.applyPreset(
            pipelineParams,
            preset
        )
        applyParameterEdit(
            "Preset: ${preset.name}",
            merged,
            R.id.nav_presets
        )
    }

    private fun randomizeParams(label: String) {
        if (!pipelineState.isImageLoaded()) return
        val rng = java.util.Random()
        val defaults = PipelineParams()
        val fitted = pipelineState.fittedParams ?: return

        fun gaussian(center: Float, sd: Float, min: Float, max: Float): Float =
            (center + rng.nextGaussian().toFloat() * sd).coerceIn(min, max)

        fun truncatedGaussian(
            center: Float,
            sd: Float,
            min: Float,
            max: Float
        ): Float {
            var sample: Float
            do {
                sample = center + rng.nextGaussian().toFloat() * sd
            } while (sample !in min..max)
            return sample
        }

        fun shapeParam(
            center: Float,
            range: ParameterRanges.ShapeRange,
            sd: Float
        ): Float {
            val rawCenter = ParameterRanges.controlToShape(center, range)
            val rawValue = gaussian(
                rawCenter,
                sd,
                range.min,
                range.max
            )
            return ParameterRanges.shapeToControl(rawValue, range)
        }

        fun angleParam(
            center: Float,
            range: ParameterRanges.ShapeRange
        ): Float =
            shapeParam(
                center,
                range,
                0.15f * (Math.PI / 2.0).toFloat()
            )

        val newParams = fitted.copy(
            // Light tab — randomize around the per-image fit
            lightSmoothing = truncatedGaussian(
                fitted.lightSmoothing,
                0.15f,
                0.0f,
                1.0f
            ),
            lightShadows = angleParam(
                fitted.lightShadows,
                ParameterRanges.TOE
            ),
            lightHighlights = angleParam(
                fitted.lightHighlights,
                ParameterRanges.SHOULDER
            ),
            lightMidtoneBalance = shapeParam(
                fitted.lightMidtoneBalance,
                ParameterRanges.BALANCE,
                0.15f
            ),
            lightMidtoneContrast = angleParam(
                fitted.lightMidtoneContrast,
                ParameterRanges.GAMMA
            ),
            lightLift = gaussian(
                fitted.lightLift,
                0.15f,
                ParameterRanges.LIFT_MIN,
                ParameterRanges.LIFT_MAX
            ),
            lightGain = gaussian(
                fitted.lightGain,
                0.15f,
                ParameterRanges.GAIN_MIN,
                ParameterRanges.GAIN_MAX
            ),

            // Color tab — randomize around the per-image fit
            colorSmoothing = truncatedGaussian(
                fitted.colorSmoothing,
                0.15f,
                0.0f,
                1.0f
            ),
            colorMutedColors = angleParam(
                fitted.colorMutedColors,
                ParameterRanges.TOE
            ),
            colorVividColors = angleParam(
                fitted.colorVividColors,
                ParameterRanges.SHOULDER
            ),
            colorSaturationBalance = shapeParam(
                fitted.colorSaturationBalance,
                ParameterRanges.BALANCE,
                0.15f
            ),
            colorVibrancy = angleParam(
                fitted.colorVibrancy,
                ParameterRanges.GAMMA
            ),
            colorLift = gaussian(
                fitted.colorLift,
                0.15f,
                ParameterRanges.LIFT_MIN,
                ParameterRanges.LIFT_MAX
            ),
            colorGain = gaussian(
                fitted.colorGain,
                0.15f,
                ParameterRanges.GAIN_MIN,
                ParameterRanges.GAIN_MAX
            ),

            // Zoned Tint — uniform hue, subtle strength
            shadowTintHue = Random.nextFloat(),
            shadowTintStrength = (
                abs(rng.nextGaussian().toFloat() * 0.08f) /
                    ParameterRanges.TINT_STRENGTH_MAX
            ).coerceIn(0.0f, 1.0f),
            midtoneTintHue = Random.nextFloat(),
            midtoneTintStrength = (
                abs(rng.nextGaussian().toFloat() * 0.08f) /
                    ParameterRanges.TINT_STRENGTH_MAX
            ).coerceIn(0.0f, 1.0f),
            highlightTintHue = Random.nextFloat(),
            highlightTintStrength = (
                abs(rng.nextGaussian().toFloat() * 0.08f) /
                    ParameterRanges.TINT_STRENGTH_MAX
            ).coerceIn(0.0f, 1.0f),

            // Vignette — exposure attenuation with varied radial falloff
            vignetteAmount = (
                abs(rng.nextGaussian().toFloat() * 0.75f) /
                    ParameterRanges.VIGNETTE_AMOUNT_MAX
            ).coerceIn(0.0f, 1.0f),
            vignetteFalloff = ParameterRanges.vignetteFalloffFromRender(
                gaussian(
                    ParameterRanges.vignetteFalloffToRender(
                        defaults.vignetteFalloff
                    ),
                    1.5f,
                    ParameterRanges.VIGNETTE_FALLOFF_MIN,
                    ParameterRanges.VIGNETTE_FALLOFF_MAX
                )
            ),

            // Grain — half-normal amount and log-normal apparent size
            grainAmount = ParameterRanges.grainAmountFromRender(
                abs(rng.nextGaussian().toFloat() * 0.04f).coerceIn(
                    0.0f,
                    ParameterRanges.GRAIN_AMOUNT_MAX
                )
            ),
            grainSize = ParameterRanges.grainSizeFromRender(
                (
                    ParameterRanges.grainSizeToRender(defaults.grainSize) *
                        exp(rng.nextGaussian() * 0.7).toFloat()
                ).coerceIn(
                    ParameterRanges.GRAIN_SIZE_MIN,
                    ParameterRanges.GRAIN_SIZE_MAX
                )
            )
        )
        ParameterRanges.requireWithinUiBounds(newParams)

        // Haptic feedback
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        applyParameterEdit(label, newParams, currentNavItemId)
    }

    fun beginParameterEdit(label: String) {
        if (!pipelineState.isImageLoaded() || activeEdit != null) return
        activeEdit = ActiveEdit(label, pipelineParams, currentNavItemId)
        updateHistoryButtons()
    }

    fun previewParameterEdit(params: PipelineParams) {
        renderParams(params)
    }

    fun mergeActiveEditWithPrevious() {
        val active = activeEdit ?: return
        val previous = undoHistory.peekLast() ?: return
        if (
            previous.label != active.label ||
            previous.after != active.before ||
            previous.navigationItemId != active.navigationItemId
        ) return
        undoHistory.removeLast()
        activeEdit = active.copy(before = previous.before)
    }

    fun commitParameterEdit() {
        val edit = activeEdit ?: return
        activeEdit = null
        recordEdit(edit.label, edit.before, pipelineParams, edit.navigationItemId)
        updateHistoryButtons()
    }

    fun applyParameterEdit(label: String, params: PipelineParams, navigationItemId: Int) {
        commitParameterEdit()
        val before = pipelineParams
        renderParams(params)
        recordEdit(label, before, params, navigationItemId)
        notifyFragmentUpdate()
        updateHistoryButtons()
    }

    private fun recordEdit(
        label: String,
        before: PipelineParams,
        after: PipelineParams,
        navigationItemId: Int
    ) {
        if (before == after) return
        undoHistory.addLast(EditEntry(label, before, after, navigationItemId))
        while (undoHistory.size > MAX_EDIT_HISTORY) {
            undoHistory.removeFirst()
        }
        redoHistory.clear()
    }

    private fun undoLastEdit() {
        if (activeEdit != null || undoHistory.isEmpty()) return
        val edit = undoHistory.removeLast()
        redoHistory.addLast(edit)
        renderParams(edit.before)
        showEditTab(edit.navigationItemId)
        updateHistoryButtons()
    }

    private fun redoLastEdit() {
        if (activeEdit != null || redoHistory.isEmpty()) return
        val edit = redoHistory.removeLast()
        undoHistory.addLast(edit)
        renderParams(edit.after)
        showEditTab(edit.navigationItemId)
        updateHistoryButtons()
    }

    private fun showEditTab(navigationItemId: Int) {
        if (bottomNav.selectedItemId == navigationItemId) {
            notifyFragmentUpdate()
        } else {
            bottomNav.selectedItemId = navigationItemId
        }
    }

    private fun clearEditHistory() {
        activeEdit = null
        undoHistory.clear()
        redoHistory.clear()
        updateHistoryButtons()
    }

    private fun updateHistoryButtons() {
        if (!::fabUndo.isInitialized || !::fabRedo.isInitialized) return
        val canUseHistory = activeEdit == null && pipelineState.isImageLoaded()
        val canUndo = canUseHistory && undoHistory.isNotEmpty()
        val canRedo = canUseHistory && redoHistory.isNotEmpty()
        fabUndo.isEnabled = canUndo
        fabRedo.isEnabled = canRedo
        fabUndo.alpha = if (canUndo) ENABLED_HISTORY_ALPHA else DISABLED_HISTORY_ALPHA
        fabRedo.alpha = if (canRedo) ENABLED_HISTORY_ALPHA else DISABLED_HISTORY_ALPHA
    }

    /**
     * Render the latest parameter snapshot. Intermediate gesture previews use
     * this without touching history; committed edits record their boundary.
     */
    private fun renderParams(params: PipelineParams) {
        ParameterRanges.requireMathematicallySafe(params)
        pipelineParams = params
        if (gpuStatus != GpuStatus.READY) return
        val lut = gamutLut
        val gpu = gpuPipeline
        val state = pipelineState
        if (!state.isImageLoaded()) return

        // Full invalidate: processing params changed, cached overlay is stale
        invalidateHiResCrop()

        pendingParams = params
        renderGeneration++

        if (!isRendering) {
            startRender(state, lut, gpu)
        }
    }

    private fun startRender(state: PipelineState, lut: FloatArray, gpu: GpuPipeline) {
        val paramsToRender = pendingParams ?: return
        val genAtStart = renderGeneration
        val imageGenAtStart = imageGeneration
        isRendering = true

        renderJob = lifecycleScope.launch {
            try {
                val outBitmap = withContext(gpuDispatcher) {
                    ImagePipeline.processFromParams(state, paramsToRender, lut, gpu)
                }
                if (imageGenAtStart != imageGeneration) return@launch
                processedBitmap = outBitmap
                state.processedBitmap = outBitmap
                if (!isShowingOriginal) {
                    updateImageBitmap(outBitmap)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                Log.e(TAG, "Render error", e)
            } finally {
                if (imageGenAtStart == imageGeneration) {
                    isRendering = false
                    if (renderGeneration != genAtStart) {
                        startRender(state, lut, gpu)
                    } else {
                        // Low-res is up to date — kick off hi-res crop if zoomed
                        maybeStartHiResCrop()
                    }
                }
            }
        }
    }

    private fun updateImageBitmap(bitmap: Bitmap) {
        imageView.setImageBitmapPreservingTransform(bitmap)
    }

    private fun previewVignetteRenderParams(
        state: PipelineState,
        params: PipelineParams
    ): GpuPipeline.VignetteRenderParams =
        GpuPipeline.VignetteRenderParams(
            amount = ParameterRanges.vignetteAmountToRender(
                params.vignetteAmount
            ),
            falloff = ParameterRanges.vignetteFalloffToRender(
                params.vignetteFalloff
            ),
            rowWidth = state.width,
            originX = 0,
            originY = 0,
            coordinateScaleX = 1.0f,
            coordinateScaleY = 1.0f,
            fullImageWidth = state.width,
            fullImageHeight = state.height
        )

    // ---------------------------------------------------------------
    // Hi-res crop overlay (second rendering track)
    // ---------------------------------------------------------------

    private fun showCachedHiResOverlay(bitmap: Bitmap?) {
        val cropRect = currentHiResCropRect
        if (
            imageView.getZoomScale() >= HIRES_ZOOM_THRESHOLD &&
            bitmap != null &&
            cropRect != null
        ) {
            imageView.setOverlay(bitmap, cropRect)
        } else {
            imageView.clearOverlay()
        }
    }

    private fun onViewportChanged() {
        // Cancel in-progress render job; keep existing overlay visible until replaced
        cancelHiResCropJob()
        val gen = ++hiResCropGeneration

        // Check if zoomed enough to warrant hi-res
        if (imageView.getZoomScale() < HIRES_ZOOM_THRESHOLD) {
            imageView.clearOverlay()
            return
        }
        if (!pipelineState.isImageLoaded()) {
            imageView.clearOverlay()
            return
        }
        val orig = originalBitmap
        if (orig == null || maxOf(orig.width, orig.height) <= ImagePipeline.MAX_PROCESSING_EDGE) {
            imageView.clearOverlay()
            return
        }

        // Debounce: wait for gesture to settle
        hiResCropJob = lifecycleScope.launch {
            delay(HIRES_DEBOUNCE_MS)
            if (gen == hiResCropGeneration) {
                startHiResCrop()
            }
        }
    }

    private fun maybeStartHiResCrop() {
        cancelHiResCropJob()
        val gen = ++hiResCropGeneration
        if (imageView.getZoomScale() < HIRES_ZOOM_THRESHOLD) {
            imageView.clearOverlay()
            return
        }
        if (!pipelineState.isImageLoaded()) {
            imageView.clearOverlay()
            return
        }
        val orig = originalBitmap
        if (orig == null || maxOf(orig.width, orig.height) <= ImagePipeline.MAX_PROCESSING_EDGE) {
            imageView.clearOverlay()
            return
        }

        hiResCropJob = lifecycleScope.launch {
            if (gen == hiResCropGeneration) {
                startHiResCrop()
            }
        }
    }

    /** Cancel only the in-progress hi-res render job; keep cached overlay visible. */
    private fun cancelHiResCropJob() {
        hiResCropJob?.cancel()
        hiResCropJob = null
    }

    /** Cancel job AND clear cached overlay — for slider changes / new image loads. */
    private fun invalidateHiResCrop() {
        cancelHiResCropJob()
        hiResCropGeneration++
        currentHiResBitmap = null
        currentOriginalHiResBitmap = null
        currentHiResCropRect = null
        imageView.clearOverlay()
    }

    private fun boundedOverlaySize(
        displayedCropRect: RectF,
        cropW: Int,
        cropH: Int
    ): OverlaySize {
        val desiredWidth =
            ceil(displayedCropRect.width().toDouble()).toInt().coerceIn(1, cropW)
        val desiredHeight =
            ceil(displayedCropRect.height().toDouble()).toInt().coerceIn(1, cropH)
        val desiredPixels = desiredWidth.toLong() * desiredHeight.toLong()
        if (desiredPixels <= MAX_HIRES_OVERLAY_PIXELS) {
            return OverlaySize(desiredWidth, desiredHeight)
        }

        val scale = sqrt(MAX_HIRES_OVERLAY_PIXELS.toDouble() / desiredPixels.toDouble())
        val width = floor(desiredWidth * scale).toInt().coerceAtLeast(1)
        val height = floor(desiredHeight * scale).toInt().coerceAtLeast(1)
        return OverlaySize(width, height)
    }

    private fun startHiResCrop() {
        val orig = originalBitmap ?: return
        val state = pipelineState
        val params = pipelineParams
        if (gpuStatus != GpuStatus.READY) return
        val lut = gamutLut
        val gpu = gpuPipeline
        if (!state.isImageLoaded()) return

        // Get visible rect in the 1024px image coordinates
        val visibleRect = imageView.getVisibleImageRect() ?: return
        val previewBm = state.originalBitmap ?: return

        // Scale visible rect from preview coordinates to original coordinates
        val scaleX = orig.width.toFloat() / previewBm.width.toFloat()
        val scaleY = orig.height.toFloat() / previewBm.height.toFloat()

        val origLeft = visibleRect.left * scaleX
        val origTop = visibleRect.top * scaleY
        val origRight = visibleRect.right * scaleX
        val origBottom = visibleRect.bottom * scaleY

        // Add padding (20% on each side)
        val padW = (origRight - origLeft) * HIRES_PADDING
        val padH = (origBottom - origTop) * HIRES_PADDING

        // Clamp to image bounds
        val cropX = (origLeft - padW).toInt().coerceIn(0, orig.width - 1)
        val cropY = (origTop - padH).toInt().coerceIn(0, orig.height - 1)
        val cropRight = (origRight + padW).toInt().coerceIn(cropX + 1, orig.width)
        val cropBottom = (origBottom + padH).toInt().coerceIn(cropY + 1, orig.height)
        val cropW = cropRight - cropX
        val cropH = cropBottom - cropY

        if (cropW <= 0 || cropH <= 0) return

        // Remember which crop region this render covers (in preview image coordinates)
        val cropRectInPreview = RectF(
            cropX / scaleX, cropY / scaleY,
            cropRight / scaleX, cropBottom / scaleY
        )
        val displayedCropRect = imageView.getDisplayedImageRect(cropRectInPreview)
        val overlaySize = boundedOverlaySize(displayedCropRect, cropW, cropH)

        val gen = hiResCropGeneration

        hiResCropJob = lifecycleScope.launch {
            try {
                val cropBitmaps = withContext(gpuDispatcher) {
                    try {
                        ImagePipeline.processHiResCrop(
                            orig,
                            cropX,
                            cropY,
                            cropW,
                            cropH,
                            overlaySize.width,
                            overlaySize.height,
                            state,
                            params,
                            lut,
                            gpu
                        )
                    } finally {
                        // A crop may fail or be cancelled after changing shared
                        // SSBOs. Always restore the preview before releasing the
                        // single-threaded GPU dispatcher.
                        val previewPixels = IntArray(state.pixelCount)
                        previewBm.getPixels(
                            previewPixels,
                            0,
                            state.width,
                            0,
                            0,
                            state.width,
                            state.height
                        )
                        gpu.processPass1NoReadback(
                            previewPixels,
                            state.pixelCount,
                            previewVignetteRenderParams(state, params)
                        )
                    }
                }

                // Only apply if still the current generation (no cancel/new render happened)
                if (gen == hiResCropGeneration) {
                    currentHiResBitmap = cropBitmaps.processedBitmap
                    currentOriginalHiResBitmap = cropBitmaps.originalBitmap
                    currentHiResCropRect = cropRectInPreview
                    val displayedBitmap = if (isShowingOriginal) {
                        cropBitmaps.originalBitmap
                    } else {
                        cropBitmaps.processedBitmap
                    }
                    imageView.setOverlay(displayedBitmap, cropRectInPreview)
                    Log.i(
                        TAG,
                        "[HiResCrop] Overlay applied: source ${cropW}x${cropH}, " +
                            "display ${overlaySize.width}x${overlaySize.height}"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (gen == hiResCropGeneration) {
                    currentHiResBitmap = null
                    currentOriginalHiResBitmap = null
                    currentHiResCropRect = null
                    imageView.clearOverlay()
                }
                Log.e(TAG, "Hi-res crop render error", error)
            }
        }
    }

    /**
     * Process the full-resolution image (tiled GPU export) then run [action].
     * Shows a progress dialog during processing.
     * Falls back to preview-resolution processedBitmap if GPU is unavailable
     * or the image wasn't downscaled.
     */
    private fun processAndExport(action: suspend (Bitmap) -> Unit) {
        val orig = originalBitmap
        val state = pipelineState
        val params = pipelineParams

        if (orig == null || !state.isImageLoaded()) {
            Toast.makeText(this, "No processed image to export", Toast.LENGTH_SHORT).show()
            return
        }
        if (gpuStatus != GpuStatus.READY) {
            if (gpuStatus == GpuStatus.UNAVAILABLE) {
                showGpuUnavailableDialog()
            } else {
                Toast.makeText(this, "GPU processing is still initializing", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val lut = gamutLut
        val gpu = gpuPipeline

        // Images that fit in the preview already have a full-resolution render.
        if (maxOf(orig.width, orig.height) <= ImagePipeline.MAX_PROCESSING_EDGE) {
            val bitmap = processedBitmap ?: return
            lifecycleScope.launch { action(bitmap) }
            return
        }

        // Show progress dialog
        val dp = resources.displayMetrics.density
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            addView(progressBar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Processing\u2026")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                // Phase 1: Tiled GPU render (0–85%)
                val fullResBitmap = withContext(gpuDispatcher) {
                    ImagePipeline.processFullResolution(
                        orig, state, params, lut, gpu,
                        onProgress = { fraction ->
                            runOnUiThread {
                                progressBar.progress = (fraction * 85).toInt()
                            }
                        }
                    )
                }

                // Phase 2: Restore preview SSBOs (85–90%)
                dialog.setTitle("Finalizing\u2026")
                progressBar.progress = 85
                withContext(gpuDispatcher) {
                    val previewBitmap = state.originalBitmap!!
                    val previewPixels = IntArray(state.pixelCount)
                    previewBitmap.getPixels(
                        previewPixels, 0, state.width,
                        0, 0, state.width, state.height
                    )
                    gpu.processPass1(
                        previewPixels,
                        state.pixelCount,
                        previewVignetteRenderParams(state, params)
                    )
                }
                progressBar.progress = 90

                // Phase 3: Export/save (90–100%)
                dialog.setTitle("Saving\u2026")
                action(fullResBitmap)
                progressBar.progress = 100

                dialog.dismiss()
            } catch (e: Exception) {
                dialog.dismiss()
                Log.e(TAG, "Full-res export failed", e)
                Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportImage() {
        processAndExport { bitmap ->
            try {
                val filename = "TGE_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TheGreatEqualizer")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, contentValues, null, null)
                    }

                    Toast.makeText(this@MainActivity, "Saved to gallery", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareImage() {
        processAndExport { bitmap ->
            val shareDir = File(cacheDir, "shared").also { it.mkdirs() }
            val file = File(shareDir, "share_temp.jpg")
            withContext(Dispatchers.IO) {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share image"))
        }
    }

    private fun initializeGpuPipeline() {
        gpuStatus = GpuStatus.INITIALIZING
        lifecycleScope.launch {
            try {
                val lut = withContext(Dispatchers.Default) {
                    OkLab.buildGamutLut()
                }
                Log.i(TAG, "Gamut LUT built")

                val pipeline = GpuPipeline()
                withContext(gpuDispatcher) {
                    pipeline.init(this@MainActivity, lut)
                }
                gamutLut = lut
                gpuPipeline = pipeline
                gpuStatus = GpuStatus.READY
                Log.i(TAG, "GPU compute pipeline initialized (GLES 3.1)")

                processImage()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                gpuStatus = GpuStatus.UNAVAILABLE
                gpuFailureDetail = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "GPU initialization failed", error)
                showGpuUnavailableDialog()
            }
        }
    }

    private fun showGpuUnavailableDialog() {
        if (gpuErrorDialog?.isShowing == true || isFinishing || isDestroyed) return

        gpuErrorDialog = AlertDialog.Builder(this)
            .setTitle("GPU processing unavailable")
            .setMessage(
                "The Great Equalizer requires OpenGL ES 3.1 compute support. " +
                    "This device or graphics driver could not initialize the required pipeline.\n\n" +
                    gpuFailureDetail
            )
            .setCancelable(false)
            .setPositiveButton("Close app") { _, _ -> finishAndRemoveTask() }
            .create()
        gpuErrorDialog?.show()
    }

    override fun onDestroy() {
        imageProcessingJob?.cancel()
        renderJob?.cancel()
        gpuErrorDialog?.dismiss()
        if (gpuStatus == GpuStatus.READY) {
            runBlocking {
                withContext(gpuDispatcher) {
                    gpuPipeline.release()
                }
            }
        }
        gpuDispatcher.close()
        super.onDestroy()
    }
}
