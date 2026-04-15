package com.thegreatequalizer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.google.android.material.slider.Slider

class TickMarkSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle
) : Slider(context, attrs, defStyleAttr) {

    var neutralTicks: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var fittedTicks: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density

    private val neutralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF  // white ~40% opacity
        strokeWidth = 1.5f * dp
        strokeCap = Paint.Cap.ROUND
    }

    private val fittedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9903DAC5.toInt()  // teal #03DAC5 ~60% opacity
        strokeWidth = 1.5f * dp
        strokeCap = Paint.Cap.ROUND
    }

    private val tickHeight = 10f * dp

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val range = valueTo - valueFrom
        if (range <= 0) return

        val trackSidePad = 16f * dp
        val trackLeft = paddingLeft + trackSidePad
        val trackRight = width - paddingRight - trackSidePad
        val trackWidth = trackRight - trackLeft
        if (trackWidth <= 0) return

        val centerY = height / 2f

        for (pos in neutralTicks) {
            if (pos < valueFrom || pos > valueTo) continue
            val fraction = (pos - valueFrom) / range
            val x = trackLeft + fraction * trackWidth
            canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, neutralPaint)
        }

        for (pos in fittedTicks) {
            if (pos < valueFrom || pos > valueTo) continue
            val fraction = (pos - valueFrom) / range
            val x = trackLeft + fraction * trackWidth
            canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, fittedPaint)
        }
    }
}
