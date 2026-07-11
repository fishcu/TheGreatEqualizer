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
        wheelShadows.setTint(currentParams.shadowTintAngle, currentParams.shadowTintStrength)
        wheelMidtones.setTint(currentParams.midtoneTintAngle, currentParams.midtoneTintStrength)
        wheelHighlights.setTint(currentParams.highlightTintAngle, currentParams.highlightTintStrength)

        bindWheel(wheelShadows, "Shadow tint") { params, angle, strength ->
            params.copy(shadowTintAngle = angle, shadowTintStrength = strength)
        }
        bindWheel(wheelMidtones, "Midtone tint") { params, angle, strength ->
            params.copy(midtoneTintAngle = angle, midtoneTintStrength = strength)
        }
        bindWheel(wheelHighlights, "Highlight tint") { params, angle, strength ->
            params.copy(highlightTintAngle = angle, highlightTintStrength = strength)
        }
    }

    /** Called from MainActivity when params change externally (randomize, new image). */
    fun updateFromParams() {
        val params = (activity as? MainActivity)?.pipelineParams ?: return
        wheelShadows.setTint(params.shadowTintAngle, params.shadowTintStrength)
        wheelMidtones.setTint(params.midtoneTintAngle, params.midtoneTintStrength)
        wheelHighlights.setTint(params.highlightTintAngle, params.highlightTintStrength)
    }

    private fun bindWheel(
        wheel: ColorWheelView,
        label: String,
        transform: (PipelineParams, Float, Float) -> PipelineParams
    ) {
        val main = requireActivity() as MainActivity
        wheel.onInteractionStart = { main.beginParameterEdit(label) }
        wheel.onDoubleTapReset = { main.mergeActiveEditWithPrevious() }
        wheel.onTintChanged = { angle, strength ->
            main.previewParameterEdit(transform(main.pipelineParams, angle, strength))
        }
        wheel.onInteractionEnd = { main.commitParameterEdit() }
    }
}
