package com.thegreatequalizer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Circular color wheel for selecting hue (angle) and strength (distance from center).
 * Center = zero strength, edge = max strength.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onTintChanged: ((hue: Float, strength: Float) -> Unit)? = null
    var onInteractionStart: (() -> Unit)? = null
    var onInteractionEnd: (() -> Unit)? = null
    var onDoubleTapReset: (() -> Unit)? = null

    /** Canonical strength corresponding to the edge of the disc. */
    var maxStrength: Float = 1.0f

    // Current selection in polar coords (OKLab convention: CCW from east)
    private var currentAngle: Float = 0f      // radians [0, 2π]
    private var currentStrength: Float = 0f   // [0, maxStrength]

    // Cached disc bitmap
    private var discBitmap: Bitmap? = null
    private var cachedRadius: Int = 0

    // Paints
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFF444444.toInt()
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFF333333.toInt()
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }

    private val centerDotRadius = 4f * resources.displayMetrics.density
    private val thumbRadius = 8f * resources.displayMetrics.density
    private val snapThreshold = 0.10f // 10% of radius for sticky center snap
    private val TWO_PI = (2 * Math.PI).toFloat()

    private var dragging = false
    private var doubleTapConsumed = false
    private var stickyAtCenter = false

    // Double-tap detector for resetting strength to zero
    private val doubleTapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTapReset?.invoke()
                doubleTapConsumed = true
                currentStrength = 0f
                invalidate()
                onTintChanged?.invoke(currentAngle / TWO_PI, currentStrength)
                return true
            }
        })

    fun setTint(hue: Float, strength: Float) {
        currentAngle = ParameterRanges.hueToRadians(hue)
        currentStrength = strength.coerceIn(0f, maxStrength)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force square
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        discBitmap?.recycle()
        discBitmap = null
    }

    private fun ensureDiscBitmap(): Bitmap {
        val size = min(width, height)
        val radius = size / 2
        if (discBitmap != null && cachedRadius == radius) return discBitmap!!

        cachedRadius = radius
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = radius.toFloat()
        val cy = radius.toFloat()
        val r = radius.toFloat() - borderPaint.strokeWidth

        // HSV hue sweep: colors go CCW to match OKLab angle convention
        // SweepGradient draws CW, so reverse the hue order (360 - hue)
        val hueColors = IntArray(13) { i ->
            Color.HSVToColor(floatArrayOf(360f - i * 30f, 1f, 1f))
        }

        val sweepShader = SweepGradient(cx, cy, hueColors, null)

        // Radial gradient: white center fading to transparent at edge
        val radialShader = RadialGradient(
            cx, cy, r,
            intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Draw the hue disc
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        discPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, r, discPaint)

        // Overlay the radial white fade on top
        discPaint.shader = radialShader
        canvas.drawCircle(cx, cy, r, discPaint)

        discBitmap = bmp
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height)
        if (size <= 0) return

        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - borderPaint.strokeWidth

        // Draw cached disc
        val bmp = ensureDiscBitmap()
        canvas.drawBitmap(bmp, 0f, 0f, null)

        // Border ring
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Center dot (neutral/zero marker)
        canvas.drawCircle(cx, cy, centerDotRadius, centerDotPaint)

        // Thumb position: convert OKLab angle (CCW) back to screen angle (CW) for drawing
        val fraction = (currentStrength / maxStrength).coerceIn(0f, 1f)
        val thumbDist = fraction * r
        val screenAngle = (TWO_PI - currentAngle) % TWO_PI
        val thumbX = cx + thumbDist * cos(screenAngle)
        val thumbY = cy + thumbDist * sin(screenAngle)

        // Draw thumb
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onInteractionStart?.invoke()
        }
        doubleTapDetector.onTouchEvent(event)

        // If a double-tap was consumed, suppress drag until finger lifts
        if (doubleTapConsumed) {
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                doubleTapConsumed = false
                onInteractionEnd?.invoke()
            }
            return true
        }

        val size = min(width, height)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - borderPaint.strokeWidth

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                stickyAtCenter = false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y, cx, cy, r)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFromTouch(event.x, event.y, cx, cy, r)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                stickyAtCenter = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onInteractionEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float, cx: Float, cy: Float, r: Float) {
        val dx = x - cx
        val dy = y - cy  // DO NOT flip Y — match SweepGradient screen coords

        var dist = hypot(dx, dy)
        // Clamp to disc radius
        if (dist > r) dist = r

        val fraction = dist / r

        if (stickyAtCenter) {
            // Stuck at center — require dragging past threshold to break free
            if (fraction < snapThreshold) {
                currentStrength = 0f
                invalidate()
                onTintChanged?.invoke(currentAngle / TWO_PI, currentStrength)
                return
            }
            stickyAtCenter = false
        } else if (fraction < snapThreshold) {
            // Entering center zone — snap, stick, and haptic
            stickyAtCenter = true
            currentStrength = 0f
            triggerHaptic()
            invalidate()
            onTintChanged?.invoke(currentAngle / TWO_PI, currentStrength)
            return
        }

        // Normal tracking outside center zone
        currentStrength = (fraction * maxStrength).coerceIn(0f, maxStrength)

        // atan2 in screen coords (CW from east)
        var screenAngle = atan2(dy, dx)
        if (screenAngle < 0) screenAngle += TWO_PI

        // Convert screen CW angle to math CCW angle for OKLab
        currentAngle = (TWO_PI - screenAngle) % TWO_PI

        invalidate()
        onTintChanged?.invoke(currentAngle / TWO_PI, currentStrength)
    }

    @Suppress("DEPRECATION")
    private fun triggerHaptic() {
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}
