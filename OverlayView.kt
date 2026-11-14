package com.example.ardrawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * খাতার চার কোণা অনুযায়ী ছবিটাকে পাতার ওপর "আটকে" আঁকে।
 * ফোন নড়লে কোণা সরে যায়, তাই ছবিও পাতার সাথে সরে — স্ক্রিনের সাথে নয়।
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var imageAlpha: Float = 0.5f
        set(value) {
            field = value
            invalidate()
        }

    private var quad: FloatArray? = null
    private var aspect = 0.75f
    private var lastSeen = 0L
    private var rot = 0
    private var landscape = false
    private var locked = false

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 0, 255, 120)
    }
    private val drawMatrix = Matrix()
    private val path = Path()

    /** ট্র্যাকার থেকে নতুন কোণা এলে ডাকুন (মেইন থ্রেডে) */
    fun update(corners: FloatArray?, frameAspect: Float) {
        if (corners != null) {
            if (!locked) {
                // প্রথমবার পাতা পেলে ঠিক করি এটা আড়াআড়ি না লম্বালম্বি
                val d01 = hypot(
                    (corners[2] - corners[0]) * frameAspect,
                    corners[3] - corners[1]
                )
                val d12 = hypot(
                    (corners[4] - corners[2]) * frameAspect,
                    corners[5] - corners[3]
                )
                landscape = d01 > d12
                locked = true
            }
            quad = corners
            aspect = frameAspect
            lastSeen = System.currentTimeMillis()
        }
        invalidate()
    }

    fun rotateImage() {
        rot = (rot + 1) % 4
        invalidate()
    }

    fun resetLock() {
        locked = false
        quad = null
        rot = 0
        invalidate()
    }

    val isTracking: Boolean
        get() = quad != null && System.currentTimeMillis() - lastSeen < 800

    override fun onDraw(canvas: Canvas) {
        val q = quad ?: return
        if (!isTracking) return

        // ০..১ স্কেল থেকে স্ক্রিনের পিক্সেলে (PreviewView-র FILL_CENTER এর মতো)
        val fw = aspect
        val fh = 1f
        val s = max(width / fw, height / fh)
        val ox = (width - fw * s) / 2f
        val oy = (height - fh * s) / 2f
        val dst = FloatArray(8)
        for (i in 0 until 4) {
            val idx = (i + rot) % 4
            dst[i * 2] = ox + q[idx * 2] * fw * s
            dst[i * 2 + 1] = oy + q[idx * 2 + 1] * fh * s
        }

        // পাতার সীমানা হালকা সবুজ রেখায়
        path.reset()
        path.moveTo(dst[0], dst[1])
        path.lineTo(dst[2], dst[3])
        path.lineTo(dst[4], dst[5])
        path.lineTo(dst[6], dst[7])
        path.close()
        canvas.drawPath(path, outlinePaint)

        val bmp = bitmap ?: return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()

        // A4 = ২১০ × ২৯৭ মিমি। ঘোরানোর ধাপ বিজোড় হলে দৈর্ঘ্য-প্রস্থ অদলবদল
        val pageLandscape = if (rot % 2 == 0) landscape else !landscape
        val pw = if (pageLandscape) 297f else 210f
        val ph = if (pageLandscape) 210f else 297f

        // ছবিটা পাতার ভেতরে মাঝখানে "ফিট" করে বসে
        val scale = min(pw / bw, ph / bh)
        val halfW = pw / (2f * scale)
        val halfH = ph / (2f * scale)
        val cx = bw / 2f
        val cy = bh / 2f
        val src = floatArrayOf(
            cx - halfW, cy - halfH,
            cx + halfW, cy - halfH,
            cx + halfW, cy + halfH,
            cx - halfW, cy + halfH
        )

        drawMatrix.reset()
        if (drawMatrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            imagePaint.alpha = (imageAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, drawMatrix, imagePaint)
        }
    }
}
