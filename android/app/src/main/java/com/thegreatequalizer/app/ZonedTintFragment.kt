package com.thegreatequalizer.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class ZonedTintFragment : Fragment() {

    private lateinit var wheelShadows: ColorWheelView
    private lateinit var wheelMidtones: ColorWheelView
    private lateinit var wheelHighlights: ColorWheelView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_zoned_tint, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wheelShadows = view.findViewById(R.id.wheel_shadows)
        wheelMidtones = view.findViewById(R.id.wheel_midtones)
        wheelHighlights = view.findViewById(R.id.wheel_highlights)

        // Set initial positions from current params
        val currentParams = (requireActivity() as MainActivity).pipelineParams
        wheelShadows.setTint(currentParams.shadowTintHue, currentParams.shadowTintStrength)
        wheelMidtones.setTint(currentParams.midtoneTintHue, currentParams.midtoneTintStrength)
        wheelHighlights.setTint(currentParams.highlightTintHue, currentParams.highlightTintStrength)

        bindWheel(wheelShadows, "Shadow tint") { params, hue, strength ->
            params.copy(shadowTintHue = hue, shadowTintStrength = strength)
        }
        bindWheel(wheelMidtones, "Midtone tint") { params, hue, strength ->
            params.copy(midtoneTintHue = hue, midtoneTintStrength = strength)
        }
        bindWheel(wheelHighlights, "Highlight tint") { params, hue, strength ->
            params.copy(highlightTintHue = hue, highlightTintStrength = strength)
        }
    }

    /** Called from MainActivity when params change externally (randomize, new image). */
    fun updateFromParams() {
        val params = (activity as? MainActivity)?.pipelineParams ?: return
        wheelShadows.setTint(params.shadowTintHue, params.shadowTintStrength)
        wheelMidtones.setTint(params.midtoneTintHue, params.midtoneTintStrength)
        wheelHighlights.setTint(params.highlightTintHue, params.highlightTintStrength)
    }

    private fun bindWheel(
        wheel: ColorWheelView,
        label: String,
        transform: (PipelineParams, Float, Float) -> PipelineParams
    ) {
        val main = requireActivity() as MainActivity
        wheel.onInteractionStart = { main.beginParameterEdit(label) }
        wheel.onDoubleTapReset = { main.mergeActiveEditWithPrevious() }
        wheel.onTintChanged = { hue, strength ->
            main.previewParameterEdit(transform(main.pipelineParams, hue, strength))
        }
        wheel.onInteractionEnd = { main.commitParameterEdit() }
    }
}
