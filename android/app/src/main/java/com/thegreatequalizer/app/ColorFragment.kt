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

class ColorFragment : Fragment() {

    private lateinit var sliderSmoothing: TickMarkSlider
    private lateinit var sliderMutedColors: TickMarkSlider
    private lateinit var sliderVividColors: TickMarkSlider
    private lateinit var sliderSaturationBalance: TickMarkSlider
    private lateinit var sliderVibrancy: TickMarkSlider
    private lateinit var sliderBlacks: TickMarkSlider
    private lateinit var sliderWhites: TickMarkSlider

    private lateinit var valueSmoothing: TextView
    private lateinit var valueMutedColors: TextView
    private lateinit var valueVividColors: TextView
    private lateinit var valueSaturationBalance: TextView
    private lateinit var valueVibrancy: TextView
    private lateinit var valueBlacks: TextView
    private lateinit var valueWhites: TextView

    private val prevValues = mutableMapOf<Int, Float>()
    private val onThreshold = mutableMapOf<Int, Float?>()
    private var settingValue = false

    // Neutral defaults in canonical control space.
    private val neutralDefaults by lazy {
        mapOf(
            R.id.slider_color_smoothing to 0.1f,
            R.id.slider_color_muted_colors to 0.5f,
            R.id.slider_color_vivid_colors to 0.5f,
            R.id.slider_color_saturation_balance to 0.5f,
            R.id.slider_color_vibrancy to 0.5f,
            R.id.slider_color_blacks to 0.0f,
            R.id.slider_color_whites to 0.0f
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_color, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sliderSmoothing = view.findViewById(R.id.slider_color_smoothing)
        sliderMutedColors = view.findViewById(R.id.slider_color_muted_colors)
        sliderVividColors = view.findViewById(R.id.slider_color_vivid_colors)
        sliderSaturationBalance = view.findViewById(R.id.slider_color_saturation_balance)
        sliderVibrancy = view.findViewById(R.id.slider_color_vibrancy)
        sliderBlacks = view.findViewById(R.id.slider_color_blacks)
        sliderWhites = view.findViewById(R.id.slider_color_whites)
        sliderBlacks.valueFrom = ParameterRanges.LIFT_MIN
        sliderBlacks.valueTo = ParameterRanges.LIFT_MAX
        sliderWhites.valueFrom = ParameterRanges.GAIN_MIN
        sliderWhites.valueTo = ParameterRanges.GAIN_MAX

        valueSmoothing = view.findViewById(R.id.value_color_smoothing)
        valueMutedColors = view.findViewById(R.id.value_color_muted_colors)
        valueVividColors = view.findViewById(R.id.value_color_vivid_colors)
        valueSaturationBalance = view.findViewById(R.id.value_color_saturation_balance)
        valueVibrancy = view.findViewById(R.id.value_color_vibrancy)
        valueBlacks = view.findViewById(R.id.value_color_blacks)
        valueWhites = view.findViewById(R.id.value_color_whites)

        // Change listeners with sticky haptic feedback
        sliderSmoothing.addOnChangeListener { slider, value, fromUser ->
            if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "C onChange: value=$value fromUser=$fromUser settingValue=$settingValue")
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueSmoothing.text = fmt(v)
            if (fromUser) {
                if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "C notifyChanged: colorSmoothing=$v")
                notifyChanged { it.copy(colorSmoothing = v) }
            }
            prevValues[slider.id] = v
        }
        sliderMutedColors.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueMutedColors.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorMutedColors = v) }
            prevValues[slider.id] = v
        }
        sliderVividColors.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueVividColors.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorVividColors = v) }
            prevValues[slider.id] = v
        }
        sliderSaturationBalance.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueSaturationBalance.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorSaturationBalance = v) }
            prevValues[slider.id] = v
        }
        sliderVibrancy.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueVibrancy.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorVibrancy = v) }
            prevValues[slider.id] = v
        }
        sliderBlacks.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueBlacks.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorLift = v) }
            prevValues[slider.id] = v
        }
        sliderWhites.addOnChangeListener { slider, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val v = if (fromUser) checkHapticAndSnap(slider, value) else value
            valueWhites.text = fmt(v)
            if (fromUser) notifyChanged { it.copy(colorGain = v) }
            prevValues[slider.id] = v
        }

        // Double-tap reset for all sliders
        for (slider in allSliders()) {
            setupDoubleTap(slider)
        }

        setSliderValues((requireActivity() as MainActivity).pipelineParams)
        updateTickMarks()

        view.findViewById<MaterialButton>(R.id.btn_fit_color).setOnClickListener {
            val main = activity as? MainActivity ?: return@setOnClickListener
            val state = main.pipelineState
            if (!state.isImageLoaded()) return@setOnClickListener
            val paramsAtPress = main.pipelineParams

            viewLifecycleOwner.lifecycleScope.launch {
                val fitted = withContext(Dispatchers.Default) {
                    ImagePipeline.fitToInput(state, paramsAtPress, "color")
                }
                main.applyParameterEdit("Fit color", fitted, R.id.nav_color)
            }
        }
    }

    private fun allSliders() = listOf(
        sliderSmoothing, sliderMutedColors, sliderVividColors,
        sliderSaturationBalance, sliderVibrancy, sliderBlacks, sliderWhites
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
            R.id.slider_color_smoothing -> notifyChanged { it.copy(colorSmoothing = v) }
            R.id.slider_color_muted_colors -> notifyChanged { it.copy(colorMutedColors = v) }
            R.id.slider_color_vivid_colors -> notifyChanged { it.copy(colorVividColors = v) }
            R.id.slider_color_saturation_balance -> notifyChanged { it.copy(colorSaturationBalance = v) }
            R.id.slider_color_vibrancy -> notifyChanged { it.copy(colorVibrancy = v) }
            R.id.slider_color_blacks -> notifyChanged { it.copy(colorLift = v) }
            R.id.slider_color_whites -> notifyChanged { it.copy(colorGain = v) }
        }
    }

    private fun editLabel(sliderId: Int): String = when (sliderId) {
        R.id.slider_color_smoothing -> "Color smoothing"
        R.id.slider_color_muted_colors -> "Muted colors"
        R.id.slider_color_vivid_colors -> "Vivid colors"
        R.id.slider_color_saturation_balance -> "Saturation balance"
        R.id.slider_color_vibrancy -> "Vibrancy"
        R.id.slider_color_blacks -> "Color lift"
        R.id.slider_color_whites -> "Color gain"
        else -> error("Unknown color slider: $sliderId")
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

        if (BuildConfig.DEBUG && slider.id == R.id.slider_color_smoothing) {
            Log.d("SmoothingDebug", "C checkHapticAndSnap: input=$newValue snapped=$snappedValue")
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
        val fitted = main.pipelineState.fittedParams ?: return null
        return when (sliderId) {
            R.id.slider_color_muted_colors -> fitted.colorMutedColors
            R.id.slider_color_vivid_colors -> fitted.colorVividColors
            R.id.slider_color_saturation_balance -> fitted.colorSaturationBalance
            R.id.slider_color_vibrancy -> fitted.colorVibrancy
            R.id.slider_color_blacks -> fitted.colorLift
            R.id.slider_color_whites -> fitted.colorGain
            else -> null
        }
    }

    private fun getFittedPos(sliderId: Int): Float? {
        return getFittedValue(sliderId)
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
        if (BuildConfig.DEBUG) Log.d("SmoothingDebug", "C setSliderValues: colorSmoothing=${params.colorSmoothing}")
        settingValue = true
        sliderSmoothing.value = params.colorSmoothing.coerceIn(sliderSmoothing.valueFrom, sliderSmoothing.valueTo)
        sliderMutedColors.value = params.colorMutedColors.coerceIn(0f, 1f)
        sliderVividColors.value = params.colorVividColors.coerceIn(0f, 1f)
        sliderSaturationBalance.value = params.colorSaturationBalance.coerceIn(0f, 1f)
        sliderVibrancy.value = params.colorVibrancy.coerceIn(0f, 1f)
        sliderBlacks.value = params.colorLift.coerceIn(sliderBlacks.valueFrom, sliderBlacks.valueTo)
        sliderWhites.value = params.colorGain.coerceIn(sliderWhites.valueFrom, sliderWhites.valueTo)
        settingValue = false

        // Update text labels (listeners were suppressed by settingValue guard)
        valueSmoothing.text = fmt(sliderSmoothing.value)
        valueMutedColors.text = fmt(sliderMutedColors.value)
        valueVividColors.text = fmt(sliderVividColors.value)
        valueSaturationBalance.text = fmt(sliderSaturationBalance.value)
        valueVibrancy.text = fmt(sliderVibrancy.value)
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
