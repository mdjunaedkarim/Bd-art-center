package com.example.ardrawing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageProcessor {

    fun process(src: Bitmap, brightness: Int, thickness: Int, removeBg: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

            if (thickness > 0) {
                val k = 2 * thickness + 1
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())
                )
                Imgproc.erode(rgb, rgb, kernel)
                kernel.release()
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (removeBg) {
                val gray = Mat()
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                val g = ByteArray(w * h)
                gray.get(0, 0, g)
                gray.release()

                val hist = IntArray(256)
                for (b in g) hist[b.toInt() and 0xFF]++
                var acc = 0
                var paper = 255
                val target = (w * h * 0.9).toInt()
                for (i in 0..255) {
                    acc += hist[i]
                    if (acc >= target) {
                        paper = i
                        break
                    }
                }

                val px = IntArray(w * h)
                for (i in px.indices) {
                    val v = g[i].toInt() and 0xFF
                    val d = paper - v
                    val a = ((d - 30 - brightness / 2) * 255 / 80).coerceIn(0, 255)
                    px[i] = (a shl 24) or 0x1A1A1A
                }
                out.setPixels(px, 0, w, 0, 0, w, h)
            } else {
                val adj = Mat()
                rgb.convertTo(adj, -1, 1.0, brightness.toDouble())
                Imgproc.cvtColor(adj, rgba, Imgproc.COLOR_RGB2RGBA)
                adj.release()
                Utils.matToBitmap(rgba, out)
            }
            return out
        } finally {
            rgba.release()
            rgb.release()
        }
    }
}
