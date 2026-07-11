package com.thegreatequalizer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LightFragment : Fragment() {

    private lateinit var sliderSmoothing: TickMarkSlider
    private lateinit var sliderShadows: TickMarkSlider
    private lateinit var sliderHighlights: TickMarkSlider
    private lateinit var sliderMidtoneBalance: TickMarkSlider
    private lateinit var sliderMidtoneContrast: TickMarkSlider
    private lateinit var sliderBlacks: TickMarkSlider
    private lateinit var sliderWhites: TickMarkSlider

    private lateinit var valueSmoothing: TextView
    private lateinit var valueShadows: TextView
    private lateinit var valueHighlights: TextView
    private lateinit var valueMidtoneBalance: TextView
    private lateinit var valueMidtoneContrast: TextView
    private lateinit var valueBlacks: TextView
    private lateinit var valueWhites: TextView

    private val prevValues = mutableMapOf<Int, Float>()
    private val onThreshold = mutableMapOf<Int, Float?>()
    private var settingValue = false

    companion object {
        private val PI_4 = (Math.PI / 4).toFloat()

        // Shape slider ranges: min, neutral, max
        private val SHADOWS_MIN = 0.01f
        private val SHADOWS_NEUTRAL = PI_4
        private val SHADOWS_MAX = 1.37f

        private val HIGHLIGHTS_MIN = 0.20f
        private val HIGHLIGHTS_NEUTRAL = PI_4
        private val HIGHLIGHTS_MAX = 1.56f

        private val MIDBAL_MIN = 0.01f
        private val MIDBAL_NEUTRAL = 0.5f
        private val MIDBAL_MAX = 0.99f

        private val MIDCON_MIN = 0.10f
        private val MIDCON_NEUTRAL = PI_4
        private val MIDCON_MAX = 1.25f

        // Position [0,1] -> internal value
        fun posToValue(pos: Float, min: Float, neutral: Float, max: Float): Float {
            return if (pos <= 0.5f) {
                min + (neutral - min) * (pos / 0.5f)
            } else {
                neutral + (max - neutral) * ((pos - 0.5f) / 0.5f)
            }
        }

        // Internal value -> position [0,1]
        fun valueToPos(value: Float, min: Float, neutral: Float, max: Float): Float {
            return if (value <= neutral) {
                0.5f * (value - min) / (neutral - min)
            } else {
                0.5f + 0.5f * (value - neutral) / (max - neutral)
            }
        }
    }

    // Neutral defaults in slider-position space (all shape sliders neutral at 0.5)
    private val neutralDefaults by lazy {
        mapOf(
            R.id.slider_light_smoothing to 0.1f,
            R.id.slider_light_shadows to 0.5f,
            R.id.slider_light_highlights to 0.5f,
            R.id.slider_light_midtone_balance to 0.5f,
            R.id.slider_light_midtone_contrast to 0.5f,
            R.id.slider_light_blacks to 0.0f,
            R.id.slider_light_whites to 0.0f
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_light, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sliderSmoothing = view.findViewById(R.id.slider_light_smoothing)
        sliderShadows = view.findViewById(R.id.slider_light_shadows)
        sliderHighlights = view.findViewById(R.id.slider_light_highlights)
        sliderMidtoneBalance = view.findViewById(R.id.slider_light_midtone_balance)
        sliderMidtoneContrast = view.findViewById(R.id.slider_light_midtone_contrast)
        sliderBlacks = view.findViewById(R.id.slider_light_blacks)
        sliderWhites = view.findViewById(R.id.slider_light_whites)

        valueSmoothing = view.findViewById(R.id.value_light_smoothing)
        valueShadows = view.findViewById(R.id.value_light_shadows)
        valueHighlights = view.findViewById(R.id.value_light_highlights)
        valueMidtoneBalance = view.findViewById(R.id.value_light_midtone_balance)
        valueMidtoneContrast = view.findViewById(R.id.value_light_midtone_contrast)
        valueBlacks = view.findViewById(R.id.value_light_blacks)
        valueWhites = view.findViewById(R.id.value_light_whites)

        // Change listeners with sticky haptic feedback
        sliderSmoothing.addOnChangeListener { slider, value, fromUser ->
            if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "L onChange: value=$value fromUser=$fromUser settingValue=$settingValue")
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueSmoothing.text = fmt(v)
            if (fromUser) {
                if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "L notifyChanged: lightStrength=${1.0f - v}")
                notifyChanged { it.copy(lightStrength = 1.0f - v) }
            }
            prevValues[slider.id] = v
        }
        sliderShadows.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueShadows.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightShadows = posToValue(v, SHADOWS_MIN, SHADOWS_NEUTRAL, SHADOWS_MAX)) }
            prevValues[slider.id] = v
        }
        sliderHighlights.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueHighlights.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightHighlights = posToValue(v, HIGHLIGHTS_MIN, HIGHLIGHTS_NEUTRAL, HIGHLIGHTS_MAX)) }
            prevValues[slider.id] = v
        }
        sliderMidtoneBalance.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueMidtoneBalance.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightMidtoneBalance = posToValue(v, MIDBAL_MIN, MIDBAL_NEUTRAL, MIDBAL_MAX)) }
            prevValues[slider.id] = v
        }
        sliderMidtoneContrast.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueMidtoneContrast.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightMidtoneContrast = posToValue(v, MIDCON_MIN, MIDCON_NEUTRAL, MIDCON_MAX)) }
            prevValues[slider.id] = v
        }
        sliderBlacks.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueBlacks.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightBlacks = v) }
            prevValues[slider.id] = v
        }
        sliderWhites.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueWhites.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(lightWhites = v) }
            prevValues[slider.id] = v
        }

        // Double-tap reset for all sliders
        for (slider in allSliders()) {
            setupDoubleTap(slider)
        }

        setSliderValues((requireActivity() as MainActivity).pipelineParams)
        updateTickMarks()

        view.findViewById<MaterialButton>(R.id.btn_fit_light).setOnClickListener {
            val main = activity as? MainActivity ?: return@setOnClickListener
            val state = main.pipelineState
            if (!state.isImageLoaded()) return@setOnClickListener
            val paramsAtPress = main.pipelineParams

            viewLifecycleOwner.lifecycleScope.launch {
                val fitted = withContext(Dispatchers.Default) {
                    ImagePipeline.fitToInput(state, paramsAtPress, "light")
                }
                main.applyParameterEdit("Fit light", fitted, R.id.nav_light)
            }
        }
    }

    private fun allSliders() = listOf(
        sliderSmoothing, sliderShadows, sliderHighlights,
        sliderMidtoneBalance, sliderMidtoneContrast, sliderBlacks, sliderWhites
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDoubleTap(slider: TickMarkSlider) {
        var ignoreUntilUp = false
        // A fast drag to the slider edge can be short enough that GestureDetector
        // classifies it as a "tap", causing a spurious double-tap reset on the
        // next touch. Track whether the previous sequence was a drag to suppress this.
        var lastTouchWasDrag = false
        var downValue = 0f
        val detector = GestureDetector(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (lastTouchWasDrag) return false
                    val defaultVal = neutralDefaults[slider.id] ?: return false
                    (activity as? MainActivity)?.mergeActiveEditWithPrevious()
                    ignoreUntilUp = true
                    slider.value = defaultVal.coerceIn(slider.valueFrom, slider.valueTo)
                    notifyChangedForSlider(slider)
                    return true
                }
            })
        slider.setOnTouchListener { _, event ->
            val main = activity as? MainActivity
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downValue = slider.value
                    main?.beginParameterEdit(editLabel(slider.id))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastTouchWasDrag = slider.value != downValue
                    main?.commitParameterEdit()
                }
            }
            detector.onTouchEvent(event)
            if (ignoreUntilUp) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    ignoreUntilUp = false
                }
                true
            } else {
                false
            }
        }
    }

    private fun notifyChangedForSlider(slider: TickMarkSlider) {
        val v = slider.value
        when (slider.id) {
            R.id.slider_light_smoothing -> notifyChanged { it.copy(lightStrength = 1.0f - v) }
            R.id.slider_light_shadows -> notifyChanged { it.copy(lightShadows = posToValue(v, SHADOWS_MIN, SHADOWS_NEUTRAL, SHADOWS_MAX)) }
            R.id.slider_light_highlights -> notifyChanged { it.copy(lightHighlights = posToValue(v, HIGHLIGHTS_MIN, HIGHLIGHTS_NEUTRAL, HIGHLIGHTS_MAX)) }
            R.id.slider_light_midtone_balance -> notifyChanged { it.copy(lightMidtoneBalance = posToValue(v, MIDBAL_MIN, MIDBAL_NEUTRAL, MIDBAL_MAX)) }
            R.id.slider_light_midtone_contrast -> notifyChanged { it.copy(lightMidtoneContrast = posToValue(v, MIDCON_MIN, MIDCON_NEUTRAL, MIDCON_MAX)) }
            R.id.slider_light_blacks -> notifyChanged { it.copy(lightBlacks = v) }
            R.id.slider_light_whites -> notifyChanged { it.copy(lightWhites = v) }
        }
    }

    private fun editLabel(sliderId: Int): String = when (sliderId) {
        R.id.slider_light_smoothing -> "Light smoothing"
        R.id.slider_light_shadows -> "Light shadows"
        R.id.slider_light_highlights -> "Light highlights"
        R.id.slider_light_midtone_balance -> "Light midtone balance"
        R.id.slider_light_midtone_contrast -> "Light midtone contrast"
        R.id.slider_light_blacks -> "Light lift"
        R.id.slider_light_whites -> "Light gain"
        else -> error("Unknown light slider: $sliderId")
    }

    private fun checkHapticAndSnap(slider: Slider, newValue: Float): Float {
        val thresholds = mutableListOf<Float>()
        neutralDefaults[slider.id]?.let { thresholds.add(it) }
        getFittedPos(slider.id)?.let { thresholds.add(it) }

        val epsilon = 0.015f
        var snappedValue = newValue
        var shouldVibrate = false
        var currentlyOn: Float? = null

        for (thresh in thresholds) {
            if (kotlin.math.abs(newValue - thresh) < epsilon) {
                snappedValue = thresh
                currentlyOn = thresh
                if (onThreshold[slider.id] != thresh) {
                    shouldVibrate = true
                }
                break
            }
        }

        onThreshold[slider.id] = currentlyOn

        if (shouldVibrate) {
            triggerHaptic(slider)
        }

        if (BuildConfig.DEBUG && slider.id == R.id.slider_light_smoothing) {
            Log.d("SmoothingDebug", "L checkHapticAndSnap: input=$newValue snapped=$snappedValue")
        }

        if (snappedValue != newValue) {
            settingValue = true
            slider.value = snappedValue.coerceIn(slider.valueFrom, slider.valueTo)
            settingValue = false
        }

        return snappedValue
    }

    @Suppress("DEPRECATION")
    private fun triggerHaptic(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /** Get fitted internal value for a slider */
    private fun getFittedValue(sliderId: Int): Float? {
        val main = activity as? MainActivity ?: return null
        val fitted = main.pipelineState.fittedDefaultsL ?: return null
        return when (sliderId) {
            R.id.slider_light_shadows -> fitted["t"]?.toFloat()
            R.id.slider_light_highlights -> fitted["s"]?.toFloat()
            R.id.slider_light_midtone_balance -> fitted["c"]?.toFloat()
            R.id.slider_light_midtone_contrast -> fitted["g"]?.toFloat()
            R.id.slider_light_blacks -> fitted["x_lo"]?.toFloat()
            R.id.slider_light_whites -> fitted["x_hi"]?.let { (it - 1.0).toFloat() }
            else -> null
        }
    }

    /** Get fitted value converted to slider position space */
    private fun getFittedPos(sliderId: Int): Float? {
        val v = getFittedValue(sliderId) ?: return null
        return when (sliderId) {
            R.id.slider_light_shadows -> valueToPos(v, SHADOWS_MIN, SHADOWS_NEUTRAL, SHADOWS_MAX)
            R.id.slider_light_highlights -> valueToPos(v, HIGHLIGHTS_MIN, HIGHLIGHTS_NEUTRAL, HIGHLIGHTS_MAX)
            R.id.slider_light_midtone_balance -> valueToPos(v, MIDBAL_MIN, MIDBAL_NEUTRAL, MIDBAL_MAX)
            R.id.slider_light_midtone_contrast -> valueToPos(v, MIDCON_MIN, MIDCON_NEUTRAL, MIDCON_MAX)
            // Blacks/Whites: no mapping, raw value IS slider position
            else -> v
        }
    }

    private fun updateTickMarks() {
        for (slider in allSliders()) {
            val neutral = mutableListOf<Float>()
            val fitted = mutableListOf<Float>()

            neutralDefaults[slider.id]?.let { v ->
                if (v in slider.valueFrom..slider.valueTo) neutral.add(v)
            }
            getFittedPos(slider.id)?.let { v ->
                if (v in slider.valueFrom..slider.valueTo) fitted.add(v)
            }

            slider.neutralTicks = neutral
            slider.fittedTicks = fitted
        }
    }

    private fun setSliderValues(params: PipelineParams) {
        if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "L setSliderValues: lightStrength=${params.lightStrength}")
        settingValue = true
        sliderSmoothing.value = (1.0f - params.lightStrength).coerceIn(sliderSmoothing.valueFrom, sliderSmoothing.valueTo)
        sliderShadows.value = valueToPos(params.lightShadows, SHADOWS_MIN, SHADOWS_NEUTRAL, SHADOWS_MAX).coerceIn(0f, 1f)
        sliderHighlights.value = valueToPos(params.lightHighlights, HIGHLIGHTS_MIN, HIGHLIGHTS_NEUTRAL, HIGHLIGHTS_MAX).coerceIn(0f, 1f)
        sliderMidtoneBalance.value = valueToPos(params.lightMidtoneBalance, MIDBAL_MIN, MIDBAL_NEUTRAL, MIDBAL_MAX).coerceIn(0f, 1f)
        sliderMidtoneContrast.value = valueToPos(params.lightMidtoneContrast, MIDCON_MIN, MIDCON_NEUTRAL, MIDCON_MAX).coerceIn(0f, 1f)
        sliderBlacks.value = params.lightBlacks.coerceIn(sliderBlacks.valueFrom, sliderBlacks.valueTo)
        sliderWhites.value = params.lightWhites.coerceIn(sliderWhites.valueFrom, sliderWhites.valueTo)
        settingValue = false

        // Update text labels (listeners were suppressed by settingValue guard)
        valueSmoothing.text = fmt(sliderSmoothing.value)
        valueShadows.text = fmt(sliderShadows.value)
        valueHighlights.text = fmt(sliderHighlights.value)
        valueMidtoneBalance.text = fmt(sliderMidtoneBalance.value)
        valueMidtoneContrast.text = fmt(sliderMidtoneContrast.value)
        valueBlacks.text = fmt(sliderBlacks.value)
        valueWhites.text = fmt(sliderWhites.value)

        // Initialize prevValues so haptic crossing detection works from the first drag
        for (slider in allSliders()) {
            prevValues[slider.id] = slider.value
        }
    }

    /** Called from MainActivity when a new image is loaded and fitted. */
    fun updateFromParams() {
        val main = activity as? MainActivity ?: return
        setSliderValues(main.pipelineParams)
        updateTickMarks()
    }

    private fun notifyChanged(transform: (PipelineParams) -> PipelineParams) {
        val main = activity as? MainActivity ?: return
        main.previewParameterEdit(transform(main.pipelineParams))
    }

    private fun fmt(value: Float): String = String.format("%.2f", value)
}
