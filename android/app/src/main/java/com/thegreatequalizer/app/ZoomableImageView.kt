package com.thegreatequalizer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView subclass with matrix-based fit-center display,
 * two-finger pinch-zoom, and two-finger pan.
 * Single-finger events are not acted on (A/B compare lives in the Activity).
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val userMatrix = Matrix()
    private val displayMatrix = Matrix()

    private val minScale = 1f
    private val maxScale = 10f
    private var currentScale = 1f

    private var lastMidX = 0f
    private var lastMidY = 0f
    private var isMultiTouch = false
    private var resetAnimator: ValueAnimator? = null
    private var doubleTapConsumed = false
    private var suppressViewportChanged = false

    // Hi-res overlay
    private var overlayBitmap: Bitmap? = null
    private var overlayRect: RectF? = null  // region in image coordinates
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Called when viewport changes (zoom or pan). */
    var onViewportChanged: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var factor = detector.scaleFactor
                val projected = currentScale * factor
                factor = when {
                    projected < minScale -> minScale / currentScale
                    projected > maxScale -> maxScale / currentScale
                    else -> factor
                }
                currentScale *= factor
                userMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampBounds()
                applyMatrix()
                return true
            }
        })

    private val doubleTapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                doubleTapConsumed = true
                animateResetToFit()
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    @SuppressWarnings("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(event)
        if (doubleTapConsumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                doubleTapConsumed = false
            }
            return true
        }

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelResetAnimation()
                // Claim the event stream so we receive multi-touch events later
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isMultiTouch = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    userMatrix.postTranslate(midX - lastMidX, midY - lastMidY)
                    clampBounds()
                    applyMatrix()
                    lastMidX = midX
                    lastMidY = midY
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isMultiTouch = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isMultiTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                snapBack()
            }
        }
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            fitCenter(bm)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bm = (drawable as? BitmapDrawable)?.bitmap ?: return
        fitCenter(bm)
    }

    private fun fitCenter(bm: Bitmap) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val bw = bm.width.toFloat()
        val bh = bm.height.toFloat()
        val scale = minOf(vw / bw, vh / bh)
        val dx = (vw - bw * scale) / 2f
        val dy = (vh - bh * scale) / 2f

        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        userMatrix.reset()
        currentScale = 1f
        applyMatrix()
    }

    /** Snap scale back to fit if user somehow zoomed out past minimum. */
    private fun snapBack() {
        if (currentScale < minScale) {
            userMatrix.reset()
            currentScale = 1f
            applyMatrix()
        }
    }

    /** Clamp translation so the image can't be panned fully off-screen. */
    private fun clampBounds() {
        val bm = (drawable as? BitmapDrawable)?.bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val rect = RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat())
        val temp = Matrix(baseMatrix)
        temp.postConcat(userMatrix)
        temp.mapRect(rect)

        var dx = 0f
        var dy = 0f

        if (rect.width() >= vw) {
            if (rect.left > 0f) dx = -rect.left
            else if (rect.right < vw) dx = vw - rect.right
        } else {
            dx = (vw - rect.width()) / 2f - rect.left
        }

        if (rect.height() >= vh) {
            if (rect.top > 0f) dy = -rect.top
            else if (rect.bottom < vh) dy = vh - rect.bottom
        } else {
            dy = (vh - rect.height()) / 2f - rect.top
        }

        if (dx != 0f || dy != 0f) {
            userMatrix.postTranslate(dx, dy)
        }
    }

    private fun animateResetToFit() {
        cancelResetAnimation()

        val startValues = FloatArray(9)
        userMatrix.getValues(startValues)
        val startScaleX = startValues[Matrix.MSCALE_X]
        val startScaleY = startValues[Matrix.MSCALE_Y]
        val startTransX = startValues[Matrix.MTRANS_X]
        val startTransY = startValues[Matrix.MTRANS_Y]

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val s = startScaleX + (1f - startScaleX) * t
                val sy = startScaleY + (1f - startScaleY) * t
                val tx = startTransX * (1f - t)
                val ty = startTransY * (1f - t)
                userMatrix.setScale(s, sy)
                userMatrix.postTranslate(tx, ty)
                currentScale = s
                applyMatrix()
            }
        }
        resetAnimator = animator
        animator.start()
    }

    private fun cancelResetAnimation() {
        resetAnimator?.cancel()
        resetAnimator = null
    }

    private fun applyMatrix() {
        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(userMatrix)
        imageMatrix = displayMatrix
        if (!suppressViewportChanged) {
            onViewportChanged?.invoke()
        }
    }

    /** Save the current user transform (for A/B bitmap swaps). */
    fun getTransformMatrix(): Matrix = Matrix(userMatrix)

    /** Restore a previously saved user transform. */
    fun setTransformMatrix(matrix: Matrix) {
        userMatrix.set(matrix)
        val values = FloatArray(9)
        userMatrix.getValues(values)
        currentScale = values[Matrix.MSCALE_X]
        applyMatrix()
    }

    /** Swap equal-sized preview bitmaps without treating it as a viewport gesture. */
    fun setImageBitmapPreservingTransform(bitmap: Bitmap) {
        val savedMatrix = getTransformMatrix()
        suppressViewportChanged = true
        try {
            setImageBitmap(bitmap)
            setTransformMatrix(savedMatrix)
        } finally {
            suppressViewportChanged = false
        }
    }

    /** Current zoom level relative to fit-to-view (1.0 = not zoomed). */
    fun getZoomScale(): Float = currentScale

    /**
     * Returns the visible rectangle of the image in image pixel coordinates,
     * or null if no image is set or not zoomed.
     */
    fun getVisibleImageRect(): RectF? {
        val bm = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return null

        // Invert the display matrix to map view coords → image coords
        val inverse = Matrix()
        if (!displayMatrix.invert(inverse)) return null

        val viewRect = RectF(0f, 0f, vw, vh)
        inverse.mapRect(viewRect)

        // Clamp to image bounds
        viewRect.left = viewRect.left.coerceIn(0f, bm.width.toFloat())
        viewRect.top = viewRect.top.coerceIn(0f, bm.height.toFloat())
        viewRect.right = viewRect.right.coerceIn(0f, bm.width.toFloat())
        viewRect.bottom = viewRect.bottom.coerceIn(0f, bm.height.toFloat())

        return viewRect
    }

    /** Maps an image-coordinate rectangle to its current on-screen rectangle. */
    fun getDisplayedImageRect(imageRect: RectF): RectF {
        val displayedRect = RectF(imageRect)
        displayMatrix.mapRect(displayedRect)
        return displayedRect
    }

    /** Set or clear the hi-res overlay bitmap. [imageRect] is in image pixel coordinates. */
    fun setOverlay(bitmap: Bitmap?, imageRect: RectF?) {
        overlayBitmap = bitmap
        overlayRect = imageRect
        invalidate()
    }

    fun clearOverlay() {
        overlayBitmap = null
        overlayRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val overlay = overlayBitmap ?: return
        val oRect = overlayRect ?: return

        // Map the overlay's image-coordinate rect through the display matrix to view coordinates
        val screenRect = RectF(oRect)
        displayMatrix.mapRect(screenRect)

        canvas.drawBitmap(overlay, null, screenRect, overlayPaint)
    }
}
