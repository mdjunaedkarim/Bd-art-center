package com.example.ardrawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val userMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val lastGoodMatrix = Matrix()
    private val camToView = Matrix()
    private val viewToCam = Matrix()
    private val homography = Matrix()

    private var imageAlpha = 180
    var isLocked = false
        private set
    private var trackingGood = true
    private var initializedPlacement = false

    private enum class Mode { NONE, DRAG, ZOOM_ROTATE }
    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var prevDist = 0f
    private var prevAngle = 0f

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        initializedPlacement = false
        isLocked = false
        trackingGood = true
        resetPlacement()
        invalidate()
    }

    fun setOpacity(percent: Int) {
        imageAlpha = (percent.coerceIn(5, 100) * 255) / 100
        invalidate()
    }

    fun lock() {
        isLocked = true
        trackingGood = true
        lastGoodMatrix.set(userMatrix)
        drawMatrix.set(userMatrix)
        invalidate()
    }

    fun unlock() {
        isLocked = false
        trackingGood = true
        drawMatrix.set(userMatrix)
        invalidate()
    }

    fun updateTracking(out: PlaneTracker.Out) {
        if (!isLocked) return
        trackingGood = out.good
        if (!out.good || width == 0 || height == 0 || out.rw == 0 || out.rh == 0) {
            invalidate()
            return
        }

        val vw = width.toFloat()
        val vh = height.toFloat()
        val rw = out.rw.toFloat()
        val rh = out.rh.toFloat()

        // PreviewView FILL_CENTER স্কেলিংয়ের সাথে ম্যাচ করা
        val scale = maxOf(vw / rw, vh / rh)
        val ox = (vw - rw * scale) / 2f
        val oy = (vh - rh * scale) / 2f

        camToView.reset()
        camToView.setScale(scale, scale)
        camToView.postTranslate(ox, oy)
        camToView.invert(viewToCam)

        homography.setValues(out.h)

        drawMatrix.set(userMatrix)
        drawMatrix.postConcat(viewToCam)
        drawMatrix.postConcat(homography)
        drawMatrix.postConcat(camToView)

        lastGoodMatrix.set(drawMatrix)
        invalidate()
    }

    private fun resetPlacement() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return

        val targetW = width * 0.68f
        val s = minOf(targetW / bmp.width, (height * 0.55f) / bmp.height)
        val dx = (width - bmp.width * s) / 2f
        val dy = (height - bmp.height * s) / 2f

        userMatrix.reset()
        userMatrix.postScale(s, s)
        userMatrix.postTranslate(dx, dy)
        drawMatrix.set(userMatrix)
        lastGoodMatrix.set(userMatrix)
        initializedPlacement = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initializedPlacement && bitmap != null) {
            resetPlacement()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (!initializedPlacement) resetPlacement()

        paint.alpha = if (isLocked && !trackingGood) (imageAlpha * 0.35f).toInt() else imageAlpha
        val m = if (isLocked) {
            if (trackingGood) drawMatrix else lastGoodMatrix
        } else {
            userMatrix
        }
        canvas.drawBitmap(bmp, m, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked || bitmap == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                mode = Mode.DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    prevMidX = (event.getX(0) + event.getX(1)) / 2f
                    prevMidY = (event.getY(0) + event.getY(1)) / 2f
                    prevDist = distance(event)
                    prevAngle = angle(event)
                    mode = Mode.ZOOM_ROTATE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        userMatrix.postTranslate(x - lastX, y - lastY)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                } else if (mode == Mode.ZOOM_ROTATE && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dist = distance(event)
                    val ang = angle(event)

                    userMatrix.postTranslate(midX - prevMidX, midY - prevMidY)
                    if (prevDist > 12f && dist > 12f) {
                        val s = (dist / prevDist).coerceIn(0.5f, 2.0f)
                        userMatrix.postScale(s, s, midX, midY)
                    }
                    val dDeg = Math.toDegrees((ang - prevAngle).toDouble()).toFloat()
                    userMatrix.postRotate(dDeg, midX, midY)

                    prevMidX = midX
                    prevMidY = midY
                    prevDist = dist
                    prevAngle = ang
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIdx = event.actionIndex
                val remainingIdx = if (upIdx == 0) 1 else 0
                if (event.pointerCount > 1 && remainingIdx < event.pointerCount) {
                    activePointerId = event.getPointerId(remainingIdx)
                    lastX = event.getX(remainingIdx)
                    lastY = event.getY(remainingIdx)
                    mode = Mode.DRAG
                } else {
                    mode = Mode.NONE
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun distance(e: MotionEvent): Float =
        hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun angle(e: MotionEvent): Float =
        atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
}
