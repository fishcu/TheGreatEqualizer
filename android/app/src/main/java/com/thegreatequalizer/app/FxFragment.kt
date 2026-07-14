package com.thegreatequalizer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.fragment.app.Fragment

class FxFragment : Fragment() {

    companion object {
        private const val DEFAULT_VIGNETTE_AMOUNT = 0.0f
        private const val DEFAULT_VIGNETTE_FALLOFF =
            ParameterRanges.DEFAULT_VIGNETTE_FALLOFF
        private const val DEFAULT_GRAIN_AMOUNT = 0.0f
        private const val DEFAULT_GRAIN_SIZE =
            ParameterRanges.DEFAULT_GRAIN_SIZE
        private const val SNAP_EPSILON_FRACTION = 0.015f
    }

    private lateinit var sliderVignetteAmount: TickMarkSlider
    private lateinit var sliderVignetteFalloff: TickMarkSlider
    private lateinit var sliderGrainAmount: TickMarkSlider
    private lateinit var sliderGrainSize: TickMarkSlider
    private lateinit var valueVignetteAmount: TextView
    private lateinit var valueVignetteFalloff: TextView
    private lateinit var valueGrainAmount: TextView
    private lateinit var valueGrainSize: TextView
    private val onDefault = mutableMapOf<Int, Boolean>()
    private var settingValue = false

    private fun grainAmountFromControl(control: Float): Float =
        control * control

    private fun grainAmountToControl(amount: Float): Float =
        kotlin.math.sqrt(amount.coerceIn(0.0f, 1.0f))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_fx, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sliderVignetteAmount = view.findViewById(R.id.slider_vignette_amount)
        sliderVignetteFalloff = view.findViewById(R.id.slider_vignette_falloff)
        sliderGrainAmount = view.findViewById(R.id.slider_grain_amount)
        sliderGrainSize = view.findViewById(R.id.slider_grain_size)
        valueVignetteAmount = view.findViewById(R.id.value_vignette_amount)
        valueVignetteFalloff = view.findViewById(R.id.value_vignette_falloff)
        valueGrainAmount = view.findViewById(R.id.value_grain_amount)
        valueGrainSize = view.findViewById(R.id.value_grain_size)

        sliderVignetteAmount.neutralTicks = listOf(DEFAULT_VIGNETTE_AMOUNT)
        sliderVignetteFalloff.neutralTicks = listOf(DEFAULT_VIGNETTE_FALLOFF)
        sliderGrainAmount.neutralTicks = listOf(DEFAULT_GRAIN_AMOUNT)
        sliderGrainSize.neutralTicks = listOf(DEFAULT_GRAIN_SIZE)

        sliderVignetteAmount.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjusted = if (fromUser) {
                snapToDefault(
                    sliderVignetteAmount,
                    value,
                    DEFAULT_VIGNETTE_AMOUNT
                )
            } else {
                value
            }
            valueVignetteAmount.text = String.format("%.2f", adjusted)
            if (fromUser) {
                notifyChanged { it.copy(vignetteAmount = adjusted) }
            }
        }
        sliderVignetteFalloff.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjusted = if (fromUser) {
                snapToDefault(
                    sliderVignetteFalloff,
                    value,
                    DEFAULT_VIGNETTE_FALLOFF
                )
            } else {
                value
            }
            valueVignetteFalloff.text = String.format("%.2f", adjusted)
            if (fromUser) {
                notifyChanged { it.copy(vignetteFalloff = adjusted) }
            }
        }
        sliderGrainAmount.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjustedControl = if (fromUser) {
                snapToDefault(sliderGrainAmount, value, DEFAULT_GRAIN_AMOUNT)
            } else {
                value
            }
            val amount = grainAmountFromControl(adjustedControl)
            valueGrainAmount.text = String.format("%.3f", amount)
            if (fromUser) {
                notifyChanged { it.copy(grainAmount = amount) }
            }
        }
        sliderGrainSize.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjusted = if (fromUser) {
                snapToDefault(sliderGrainSize, value, DEFAULT_GRAIN_SIZE)
            } else {
                value
            }
            valueGrainSize.text = String.format("%.2f", adjusted)
            if (fromUser) {
                notifyChanged { it.copy(grainSize = adjusted) }
            }
        }

        setupInteraction(
            sliderVignetteAmount,
            DEFAULT_VIGNETTE_AMOUNT,
            "Vignette amount"
        )
        setupInteraction(
            sliderVignetteFalloff,
            DEFAULT_VIGNETTE_FALLOFF,
            "Vignette falloff"
        )
        setupInteraction(sliderGrainAmount, DEFAULT_GRAIN_AMOUNT, "Grain amount")
        setupInteraction(sliderGrainSize, DEFAULT_GRAIN_SIZE, "Grain size")
        setSliderValues((requireActivity() as MainActivity).pipelineParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteraction(
        slider: TickMarkSlider,
        defaultValue: Float,
        editLabel: String
    ) {
        var ignoreUntilUp = false
        var previousTouchWasDrag = false
        var currentTouchWasDrag = false
        var downX = 0.0f
        var downY = 0.0f
        val touchSlop = ViewConfiguration.get(requireContext())
            .scaledTouchSlop
            .toFloat()
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    if (previousTouchWasDrag) return false
                    (activity as? MainActivity)?.mergeActiveEditWithPrevious()
                    ignoreUntilUp = true
                    slider.value = defaultValue
                    notifyChangedForSlider(slider)
                    return true
                }
            }
        )

        slider.setOnTouchListener { _, event ->
            val main = activity as? MainActivity
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    currentTouchWasDrag = false
                    main?.beginParameterEdit(editLabel)
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - downX
                    val deltaY = event.y - downY
                    if (deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
                        currentTouchWasDrag = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    previousTouchWasDrag = currentTouchWasDrag
                    main?.commitParameterEdit()
                }
            }
            detector.onTouchEvent(event)
            if (ignoreUntilUp) {
                if (
                    event.action == MotionEvent.ACTION_UP ||
                    event.action == MotionEvent.ACTION_CANCEL
                ) {
                    ignoreUntilUp = false
                }
                true
            } else {
                false
            }
        }
    }

    private fun snapToDefault(
        slider: TickMarkSlider,
        value: Float,
        defaultValue: Float
    ): Float {
        val epsilon = (slider.valueTo - slider.valueFrom) * SNAP_EPSILON_FRACTION
        val isNearDefault = kotlin.math.abs(value - defaultValue) < epsilon
        if (isNearDefault && onDefault[slider.id] != true) {
            triggerHaptic(slider)
        }
        onDefault[slider.id] = isNearDefault

        if (!isNearDefault || value == defaultValue) return value
        settingValue = true
        slider.value = defaultValue
        settingValue = false
        return defaultValue
    }

    private fun notifyChangedForSlider(slider: TickMarkSlider) {
        when (slider.id) {
            R.id.slider_vignette_amount -> {
                notifyChanged { it.copy(vignetteAmount = slider.value) }
            }
            R.id.slider_vignette_falloff -> {
                notifyChanged { it.copy(vignetteFalloff = slider.value) }
            }
            R.id.slider_grain_amount -> {
                notifyChanged {
                    it.copy(
                        grainAmount = grainAmountFromControl(slider.value)
                    )
                }
            }
            R.id.slider_grain_size -> {
                notifyChanged { it.copy(grainSize = slider.value) }
            }
            else -> error("Unknown FX slider: ${slider.id}")
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerHaptic(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun setSliderValues(params: PipelineParams) {
        settingValue = true
        sliderVignetteAmount.value = params.vignetteAmount.coerceIn(
            sliderVignetteAmount.valueFrom,
            sliderVignetteAmount.valueTo
        )
        sliderVignetteFalloff.value = params.vignetteFalloff.coerceIn(
            sliderVignetteFalloff.valueFrom,
            sliderVignetteFalloff.valueTo
        )
        sliderGrainAmount.value = grainAmountToControl(
            params.grainAmount
        ).coerceIn(
            sliderGrainAmount.valueFrom,
            sliderGrainAmount.valueTo
        )
        sliderGrainSize.value = params.grainSize.coerceIn(
            sliderGrainSize.valueFrom,
            sliderGrainSize.valueTo
        )
        settingValue = false
        valueVignetteAmount.text =
            String.format("%.2f", sliderVignetteAmount.value)
        valueVignetteFalloff.text =
            String.format("%.2f", sliderVignetteFalloff.value)
        valueGrainAmount.text = String.format(
            "%.3f",
            grainAmountFromControl(sliderGrainAmount.value)
        )
        valueGrainSize.text = String.format("%.2f", sliderGrainSize.value)
        onDefault[sliderVignetteAmount.id] =
            sliderVignetteAmount.value == DEFAULT_VIGNETTE_AMOUNT
        onDefault[sliderVignetteFalloff.id] =
            sliderVignetteFalloff.value == DEFAULT_VIGNETTE_FALLOFF
        onDefault[sliderGrainAmount.id] =
            sliderGrainAmount.value == DEFAULT_GRAIN_AMOUNT
        onDefault[sliderGrainSize.id] =
            sliderGrainSize.value == DEFAULT_GRAIN_SIZE
    }

    fun updateFromParams() {
        val main = activity as? MainActivity ?: return
        setSliderValues(main.pipelineParams)
    }

    private fun notifyChanged(transform: (PipelineParams) -> PipelineParams) {
        val main = activity as? MainActivity ?: return
        main.previewParameterEdit(transform(main.pipelineParams))
    }
}
