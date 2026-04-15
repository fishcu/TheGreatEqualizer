package com.thegreatequalizer.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Custom ViewGroup that arranges three labeled color wheels adaptively.
 *
 * Expects 6 children in order: label0, wheel0, label1, wheel1, label2, wheel2.
 * Child pairs: (Shadows, Midtones, Highlights).
 *
 * Portrait (wide): inverted-triangle V shape — two on top, one bottom center.
 * Landscape (narrow panel): vertical zigzag — S left, M right-offset, H left.
 */
class TintLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val margin = (8 * dp).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        if (childCount < 6) return

        val isNarrow = w < h * 0.8f
        val d = computeCircleDiameter(w, h, isNarrow)

        val labelWSpec = MeasureSpec.makeMeasureSpec(d, MeasureSpec.AT_MOST)
        val labelHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val wheelSpec = MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY)

        for (i in 0 until childCount step 2) {
            getChildAt(i).measure(labelWSpec, labelHSpec)   // label
            getChildAt(i + 1).measure(wheelSpec, wheelSpec)  // wheel
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        if (childCount < 6) return

        val isNarrow = w < h * 0.8f
        val d = getChildAt(1).measuredWidth
        val labelH = getChildAt(0).measuredHeight

        if (!isNarrow) layoutPortrait(w, h, d, labelH)
        else layoutLandscape(w, h, d, labelH)
    }

    /**
     * Portrait: wide V shape.
     * Top row: Shadows (left) + Highlights (right), each with label above wheel.
     * Bottom row: Midtones centered below, wheel-top shifted down by 0.7*d from top wheel-top.
     */
    private fun layoutPortrait(w: Int, h: Int, d: Int, labelH: Int) {
        val gap = maxOf((d * 0.15f).toInt(), d / 2 + margin)
        val wheelShift = (d * 0.7f).toInt()  // wheel-top to wheel-top vertical distance

        // Top pair starts at topY: label then wheel
        // Bottom wheel top = topWheelY + wheelShift, bottom label above that
        // Total height: labelH + wheelShift + d  (label + shift-includes-label-overlap + wheel)
        val totalH = labelH + wheelShift + d
        val topY = ((h - totalH) / 2).coerceAtLeast(margin)

        val topWheelY = topY + labelH
        val bottomWheelY = topWheelY + wheelShift
        val bottomLabelY = bottomWheelY - labelH

        // Horizontal positions
        val leftX = (w / 2) - d - gap / 2
        val rightX = (w / 2) + gap / 2
        val bottomX = (w - d) / 2

        placePair(0, leftX, topY, d, labelH)       // Shadows top-left
        placePair(2, rightX, topY, d, labelH)       // Highlights top-right
        placePair(1, bottomX, bottomLabelY, d, labelH)  // Midtones bottom-center
    }

    /**
     * Landscape (narrow panel): vertical zigzag.
     * Three pairs stacked with hex-packing overlap: each pair start offset by
     * (d*0.7 + labelH) from the previous, alternating horizontal offset.
     */
    private fun layoutLandscape(w: Int, h: Int, d: Int, labelH: Int) {
        val horizOffset = (d * 0.25f).toInt()
        // Step between pair starts: wheel overlap 0.7*d means wheel-to-wheel is 0.7*d,
        // but each pair has a label, so pair-start to pair-start = d*0.7 + labelH
        val stepY = (d * 0.7f).toInt() + labelH

        // Total: 2 * stepY + labelH + d
        val totalH = 2 * stepY + labelH + d
        val startY = ((h - totalH) / 2).coerceAtLeast(margin)
        val startX = ((w - d - horizOffset) / 2).coerceAtLeast(margin)

        placePair(0, startX, startY, d, labelH)                       // Shadows left
        placePair(1, startX + horizOffset, startY + stepY, d, labelH)  // Midtones offset-right
        placePair(2, startX, startY + 2 * stepY, d, labelH)           // Highlights left
    }

    /** Layout a label + wheel pair. Label centered above wheel. */
    private fun placePair(pairIndex: Int, x: Int, y: Int, d: Int, labelH: Int) {
        val label = getChildAt(pairIndex * 2)
        val wheel = getChildAt(pairIndex * 2 + 1)

        val labelW = label.measuredWidth
        val labelX = x + (d - labelW) / 2
        label.layout(labelX, y, labelX + labelW, y + labelH)
        wheel.layout(x, y + labelH, x + d, y + labelH + d)
    }

    private fun computeCircleDiameter(w: Int, h: Int, isNarrow: Boolean): Int {
        val labelEst = (18 * dp).toInt()
        val d = if (!isNarrow) {
            // Width: 2*d + gap(d/2+margin) + margins → 2.5*d + margin ≤ w - 2*margin
            val fromWidth = ((w - 3 * margin) / 2.5f).toInt()
            // Height: labelH + 0.7*d + d = labelH + 1.7*d ≤ h - 2*margin
            val fromHeight = ((h - 2 * margin - labelEst) / 1.7f).toInt()
            minOf(fromWidth, fromHeight)
        } else {
            // Width: d + 0.25*d + margins → 1.25*d ≤ w - 2*margin
            val fromWidth = ((w - 2 * margin) / 1.25f).toInt()
            // Height: 2*(0.7*d + labelH) + labelH + d = 2.4*d + 3*labelH ≤ h - 2*margin
            val fromHeight = ((h - 2 * margin - 3 * labelEst) / 2.4f).toInt()
            minOf(fromWidth, fromHeight)
        }
        return d.coerceAtLeast((60 * dp).toInt())
    }
}
