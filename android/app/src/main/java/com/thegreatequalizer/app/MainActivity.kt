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
import kotlin.math.exp
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

    private enum class GpuStatus {
        INITIALIZING,
        READY,
        UNAVAILABLE
    }

    companion object {
        private const val TAG = "TheGreatEqualizer"
        private const val AB_DELAY_MS = 100L
        private const val HIRES_ZOOM_THRESHOLD = 1.5f
        private const val HIRES_DEBOUNCE_MS = 150L
        private const val HIRES_PADDING = 0.2f  // 20% extra on each side
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

    private lateinit var shakeDetector: ShakeDetector

    // Hi-res crop overlay state
    private var hiResCropJob: Job? = null
    private var hiResCropGeneration = 0L
    private var currentHiResBitmap: Bitmap? = null
    private var currentHiResCropRect: RectF? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { loadImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        shakeDetector = ShakeDetector(this) { runOnUiThread { randomizeParams("Shake randomize") } }

        // Set initial fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, LightFragment())
                .commit()
        }

        initializeGpuPipeline()

        handleImageShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImageShareIntent(intent)
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
                    val orig = originalBitmap
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
                    val hiResBmp = currentHiResBitmap
                    val hiResRect = currentHiResCropRect
                    if (hiResBmp != null && hiResRect != null) {
                        imageView.setOverlay(hiResBmp, hiResRect)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                abDelayJob?.cancel()
                abDelayJob = null
                if (isShowingOriginal) {
                    isShowingOriginal = false
                    val bmp = processedBitmap
                    if (bmp != null) updateImageBitmap(bmp)
                    val hiResBmp = currentHiResBitmap
                    val hiResRect = currentHiResCropRect
                    if (hiResBmp != null && hiResRect != null) {
                        imageView.setOverlay(hiResBmp, hiResRect)
                    }
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

    private fun handleImageShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) {
            return
        }

        val uri = sharedImageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "No image was included in the share", Toast.LENGTH_SHORT).show()
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
                imageView.setImageBitmap(bitmap)
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
        }
    }

    private fun randomizeParams(label: String) {
        if (!pipelineState.isImageLoaded()) return
        val rng = java.util.Random()
        val defaults = PipelineParams()
        val fitted = pipelineState.fittedParams ?: return
        val pi = Math.PI.toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()

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

        fun fittedParam(center: Float, name: String, sd: Float): Float {
            val bounds = FitParams.PARAM_BOUNDS[name]!!
            return gaussian(
                center,
                sd,
                bounds.first.toFloat(),
                bounds.second.toFloat()
            )
        }

        fun angleParam(center: Float, name: String): Float =
            fittedParam(center, name, 0.15f * (pi / 2))

        val newParams = fitted.copy(
            // Light tab — randomize around the per-image fit
            lightStrength = 1.0f - truncatedGaussian(
                1.0f - fitted.lightStrength,
                0.15f,
                0.0f,
                1.0f
            ),
            lightShadows = angleParam(fitted.lightShadows, "t"),
            lightHighlights = angleParam(fitted.lightHighlights, "s"),
            lightMidtoneBalance = fittedParam(
                fitted.lightMidtoneBalance,
                "c",
                0.15f
            ),
            lightMidtoneContrast = angleParam(
                fitted.lightMidtoneContrast,
                "g"
            ),
            lightBlacks = gaussian(fitted.lightBlacks, 0.15f, -1f, 1f),
            lightWhites = gaussian(fitted.lightWhites, 0.15f, -1f, 1f),

            // Color tab — randomize around the per-image fit
            colorStrength = 1.0f - truncatedGaussian(
                1.0f - fitted.colorStrength,
                0.15f,
                0.0f,
                1.0f
            ),
            colorMutedColors = angleParam(fitted.colorMutedColors, "t"),
            colorVividColors = angleParam(fitted.colorVividColors, "s"),
            colorSaturationBalance = fittedParam(
                fitted.colorSaturationBalance,
                "c",
                0.15f
            ),
            colorVibrancy = angleParam(fitted.colorVibrancy, "g"),
            colorBlacks = gaussian(fitted.colorBlacks, 0.15f, -1f, 1f),
            colorWhites = gaussian(fitted.colorWhites, 0.15f, -1f, 1f),

            // Zoned Tint — uniform angle, subtle strength
            shadowTintAngle = Random.nextFloat() * twoPi,
            shadowTintStrength = abs(rng.nextGaussian().toFloat() * 0.08f).coerceIn(0f, 0.25f),
            midtoneTintAngle = Random.nextFloat() * twoPi,
            midtoneTintStrength = abs(rng.nextGaussian().toFloat() * 0.08f).coerceIn(0f, 0.25f),
            highlightTintAngle = Random.nextFloat() * twoPi,
            highlightTintStrength = abs(rng.nextGaussian().toFloat() * 0.08f).coerceIn(0f, 0.25f),

            // Vignette — exposure attenuation with varied radial falloff
            vignetteAmount = abs(rng.nextGaussian().toFloat() * 0.75f).coerceIn(0f, 10f),
            vignetteFalloff = gaussian(defaults.vignetteFalloff, 1.5f, 1f, 10f),

            // Grain — half-normal amount and log-normal apparent size
            grainAmount = abs(rng.nextGaussian().toFloat() * 0.04f).coerceIn(0f, 0.15f),
            grainSize = (
                defaults.grainSize * exp(rng.nextGaussian() * 0.7).toFloat()
            ).coerceIn(0.25f, 4.0f)
        )

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
        val savedMatrix = imageView.getTransformMatrix()
        imageView.setImageBitmap(bitmap)
        imageView.setTransformMatrix(savedMatrix)
    }

    private fun previewVignetteRenderParams(
        state: PipelineState,
        params: PipelineParams
    ): GpuPipeline.VignetteRenderParams =
        GpuPipeline.VignetteRenderParams(
            amount = params.vignetteAmount,
            falloff = params.vignetteFalloff,
            rowWidth = state.width,
            originX = 0,
            originY = 0,
            fullImageWidth = state.width,
            fullImageHeight = state.height
        )

    // ---------------------------------------------------------------
    // Hi-res crop overlay (second rendering track)
    // ---------------------------------------------------------------

    private fun onViewportChanged() {
        // Cancel in-progress render job; keep existing overlay visible until replaced
        cancelHiResCropJob()

        // Check if zoomed enough to warrant hi-res
        if (imageView.getZoomScale() < HIRES_ZOOM_THRESHOLD) return
        if (!pipelineState.isImageLoaded()) return
        val orig = originalBitmap ?: return
        if (maxOf(orig.width, orig.height) <= ImagePipeline.MAX_PROCESSING_EDGE) return

        // Debounce: wait for gesture to settle
        val gen = ++hiResCropGeneration
        hiResCropJob = lifecycleScope.launch {
            delay(HIRES_DEBOUNCE_MS)
            if (gen == hiResCropGeneration) {
                startHiResCrop()
            }
        }
    }

    private fun maybeStartHiResCrop() {
        if (imageView.getZoomScale() < HIRES_ZOOM_THRESHOLD) return
        if (!pipelineState.isImageLoaded()) return
        val orig = originalBitmap ?: return
        if (maxOf(orig.width, orig.height) <= ImagePipeline.MAX_PROCESSING_EDGE) return

        cancelHiResCropJob()
        val gen = ++hiResCropGeneration
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
        hiResCropJob?.cancel()
        hiResCropJob = null
        currentHiResBitmap = null
        currentHiResCropRect = null
        imageView.clearOverlay()
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

        val gen = hiResCropGeneration

        hiResCropJob = lifecycleScope.launch {
            try {
                val cropBitmap = withContext(gpuDispatcher) {
                    val result = ImagePipeline.processHiResCrop(
                        orig, cropX, cropY, cropW, cropH,
                        state, params, lut, gpu
                    )
                    // Restore preview SSBOs so low-res renders continue to work
                    val previewPixels = IntArray(state.pixelCount)
                    previewBm.getPixels(
                        previewPixels, 0, state.width,
                        0, 0, state.width, state.height
                    )
                    gpu.processPass1(
                        previewPixels,
                        state.pixelCount,
                        previewVignetteRenderParams(state, params)
                    )
                    result
                }

                // Only apply if still the current generation (no cancel/new render happened)
                if (gen == hiResCropGeneration && !isShowingOriginal) {
                    currentHiResBitmap = cropBitmap
                    currentHiResCropRect = cropRectInPreview
                    imageView.setOverlay(cropBitmap, cropRectInPreview)
                    Log.i(TAG, "[HiResCrop] Overlay applied: ${cropW}x${cropH}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hi-res crop render error", e)
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
