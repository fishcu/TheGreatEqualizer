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
        val params = (requireActivity() as MainActivity).pipelineParams
        wheelShadows.setTint(params.shadowTintAngle, params.shadowTintStrength)
        wheelMidtones.setTint(params.midtoneTintAngle, params.midtoneTintStrength)
        wheelHighlights.setTint(params.highlightTintAngle, params.highlightTintStrength)

        // Wire up callbacks
        wheelShadows.onTintChanged = { angle, strength ->
            notifyChanged { it.copy(shadowTintAngle = angle, shadowTintStrength = strength) }
        }
        wheelMidtones.onTintChanged = { angle, strength ->
            notifyChanged { it.copy(midtoneTintAngle = angle, midtoneTintStrength = strength) }
        }
        wheelHighlights.onTintChanged = { angle, strength ->
            notifyChanged { it.copy(highlightTintAngle = angle, highlightTintStrength = strength) }
        }
    }

    /** Called from MainActivity when params change externally (randomize, new image). */
    fun updateFromParams() {
        val params = (activity as? MainActivity)?.pipelineParams ?: return
        wheelShadows.setTint(params.shadowTintAngle, params.shadowTintStrength)
        wheelMidtones.setTint(params.midtoneTintAngle, params.midtoneTintStrength)
        wheelHighlights.setTint(params.highlightTintAngle, params.highlightTintStrength)
    }

    private fun notifyChanged(transform: (PipelineParams) -> PipelineParams) {
        val main = activity as? MainActivity ?: return
        main.onParamsChanged(transform(main.pipelineParams))
    }
}
