package com.example.ardrawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Samples {
    const val COUNT = 5

    fun render(index: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(size / 600f, size / 600f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            color = 0xFF1A1A1A.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        when (index) {
            0 -> house(c, p)
            1 -> flower(c, p)
            2 -> fish(c, p, fill)
            3 -> cat(c, p, fill)
            else -> star(c, p)
        }
        return bmp
    }

    private fun house(c: Canvas, p: Paint) {
        c.drawRect(150f, 280f, 450f, 500f, p)
        val roof = Path().apply {
            moveTo(120f, 280f)
            lineTo(300f, 120f)
            lineTo(480f, 280f)
            close()
        }
        c.drawPath(roof, p)
        c.drawRect(270f, 380f, 340f, 500f, p)
        c.drawRect(180f, 320f, 240f, 380f, p)
        c.drawRect(370f, 320f, 420f, 380f, p)
        c.drawLine(300f, 120f, 300f, 60f, p)
    }

    private fun flower(c: Canvas, p: Paint) {
        for (i in 0 until 6) {
            val a = i * PI / 3.0
            c.drawCircle(300f + (cos(a) * 85).toFloat(), 230f + (sin(a) * 85).toFloat(), 45f, p)
        }
        c.drawCircle(300f, 230f, 38f, p)
        c.drawLine(300f, 360f, 300f, 550f, p)
        c.drawOval(RectF(305f, 440f, 405f, 490f), p)
        c.drawOval(RectF(195f, 410f, 295f, 460f), p)
    }

    private fun fish(c: Canvas, p: Paint, fill: Paint) {
        c.drawOval(RectF(110f, 230f, 410f, 370f), p)
        val tail = Path().apply {
            moveTo(410f, 300f)
            lineTo(500f, 230f)
            lineTo(500f, 370f)
            close()
        }
        c.drawPath(tail, p)
        c.drawCircle(180f, 285f, 12f, fill)
        val fin = Path().apply {
            moveTo(250f, 233f)
            quadTo(300f, 160f, 340f, 237f)
        }
        c.drawPath(fin, p)
        c.drawArc(RectF(230f, 265f, 300f, 335f), -90f, 180f, false, p)
        c.drawArc(RectF(280f, 265f, 350f, 335f), -90f, 180f, false, p)
    }

    private fun cat(c: Canvas, p: Paint, fill: Paint) {
        c.drawCircle(300f, 330f, 150f, p)
        val earL = Path().apply {
            moveTo(175f, 240f)
            lineTo(190f, 110f)
            lineTo(285f, 190f)
        }
        val earR = Path().apply {
            moveTo(425f, 240f)
            lineTo(410f, 110f)
            lineTo(315f, 190f)
        }
        c.drawPath(earL, p)
        c.drawPath(earR, p)
        c.drawCircle(250f, 305f, 15f, fill)
        c.drawCircle(350f, 305f, 15f, fill)
        val nose = Path().apply {
            moveTo(285f, 345f)
            lineTo(315f, 345f)
            lineTo(300f, 365f)
            close()
        }
        c.drawPath(nose, p)
        c.drawLine(300f, 365f, 300f, 385f, p)
        c.drawArc(RectF(260f, 365f, 300f, 410f), 0f, 180f, false, p)
        c.drawArc(RectF(300f, 365f, 340f, 410f), 0f, 180f, false, p)
        c.drawLine(150f, 350f, 230f, 360f, p)
        c.drawLine(150f, 395f, 230f, 380f, p)
        c.drawLine(450f, 350f, 370f, 360f, p)
        c.drawLine(450f, 395f, 370f, 380f, p)
    }

    private fun star(c: Canvas, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val r = if (i % 2 == 0) 240.0 else 100.0
            val x = (300 + cos(a) * r).toFloat()
            val y = (310 + sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }
}
