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

class GrainFragment : Fragment() {

    companion object {
        private const val DEFAULT_AMOUNT = 0.0f
        private const val DEFAULT_SIZE = 1.25f
        private const val SNAP_EPSILON_FRACTION = 0.015f
    }

    private lateinit var sliderAmount: TickMarkSlider
    private lateinit var sliderSize: TickMarkSlider
    private lateinit var valueAmount: TextView
    private lateinit var valueSize: TextView
    private val onDefault = mutableMapOf<Int, Boolean>()
    private var settingValue = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_grain, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sliderAmount = view.findViewById(R.id.slider_grain_amount)
        sliderSize = view.findViewById(R.id.slider_grain_size)
        valueAmount = view.findViewById(R.id.value_grain_amount)
        valueSize = view.findViewById(R.id.value_grain_size)

        sliderAmount.neutralTicks = listOf(DEFAULT_AMOUNT)
        sliderSize.neutralTicks = listOf(DEFAULT_SIZE)

        sliderAmount.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjusted = if (fromUser) {
                snapToDefault(sliderAmount, value, DEFAULT_AMOUNT)
            } else {
                value
            }
            valueAmount.text = String.format("%.3f", adjusted)
            if (fromUser) {
                notifyChanged { it.copy(grainAmount = adjusted) }
            }
        }
        sliderSize.addOnChangeListener { _, value, fromUser ->
            if (settingValue) return@addOnChangeListener
            val adjusted = if (fromUser) {
                snapToDefault(sliderSize, value, DEFAULT_SIZE)
            } else {
                value
            }
            valueSize.text = String.format("%.2f", adjusted)
            if (fromUser) {
                notifyChanged { it.copy(grainSize = adjusted) }
            }
        }

        setupInteraction(sliderAmount, DEFAULT_AMOUNT, "Grain amount")
        setupInteraction(sliderSize, DEFAULT_SIZE, "Grain size")
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
            R.id.slider_grain_amount -> {
                notifyChanged { it.copy(grainAmount = slider.value) }
            }
            R.id.slider_grain_size -> {
                notifyChanged { it.copy(grainSize = slider.value) }
            }
            else -> error("Unknown grain slider: ${slider.id}")
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
        sliderAmount.value = params.grainAmount.coerceIn(
            sliderAmount.valueFrom,
            sliderAmount.valueTo
        )
        sliderSize.value = params.grainSize.coerceIn(
            sliderSize.valueFrom,
            sliderSize.valueTo
        )
        settingValue = false
        valueAmount.text = String.format("%.3f", sliderAmount.value)
        valueSize.text = String.format("%.2f", sliderSize.value)
        onDefault[sliderAmount.id] = sliderAmount.value == DEFAULT_AMOUNT
        onDefault[sliderSize.id] = sliderSize.value == DEFAULT_SIZE
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
