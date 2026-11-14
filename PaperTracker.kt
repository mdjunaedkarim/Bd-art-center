package com.example.ardrawing

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * ক্যামেরার ফ্রেম থেকে খাতার (সাদা পাতার) চার কোণা খুঁজে বের করে।
 * কোণাগুলো ০..১ স্কেলে (সোজা করা ফ্রেমের হিসাবে) ফেরত দেয়,
 * আর সবসময় একই ক্রমে (পাতার একই কোণা সবসময় একই নম্বরে) রাখে।
 */
class PaperTracker {

    class Result(
        /** x0,y0,x1,y1,x2,y2,x3,y3 — ০..১ স্কেলে, ঘড়ির কাঁটার দিকে */
        val corners: FloatArray,
        /** সোজা করা ফ্রেমের প্রস্থ ÷ উচ্চতা */
        val aspect: Float
    )

    private var prev: FloatArray? = null

    fun reset() {
        prev = null
    }

    fun detect(image: ImageProxy): Result? {
        val mats = ArrayList<Mat>()
        try {
            // ১) Y (ধূসর) প্লেন থেকে Mat বানাই
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val data = ByteArray(w * h)
            if (rowStride == w) {
                buf.get(data, 0, w * h)
            } else {
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(data, row * w, w)
                }
            }
            val gray = Mat(h, w, CvType.CV_8UC1).also { mats.add(it) }
            gray.put(0, 0, data)

            // ২) ছোট করি (দ্রুত কাজের জন্য)
            val targetW = 480.0
            val small = Mat().also { mats.add(it) }
            Imgproc.resize(gray, small, Size(targetW, h * targetW / w))

            // ৩) ফোনের ঘোরানো অনুযায়ী সোজা করি
            val upright = Mat().also { mats.add(it) }
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(small, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(small, upright, Core.ROTATE_180)
                270 -> Core.rotate(small, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> small.copyTo(upright)
            }
            val rw = upright.cols()
            val rh = upright.rows()
            val frameArea = rw.toDouble() * rh

            // ৪) এজ বের করি
            val blur = Mat().also { mats.add(it) }
            Imgproc.GaussianBlur(upright, blur, Size(5.0, 5.0), 0.0)
            val edges = Mat().also { mats.add(it) }
            Imgproc.Canny(blur, edges, 50.0, 150.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            mats.add(kernel)
            Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)

            // ৫) সবচেয়ে বড় উত্তল চারকোণা আকার খুঁজি
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat().also { mats.add(it) }
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            var bestArea = 0.0
            var bestPts: Array<Point>? = null
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < frameArea * 0.08 || area > frameArea * 0.97 || area <= bestArea) {
                    c.release()
                    continue
                }
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                val pts = approx.toArray()
                if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                    bestArea = area
                    bestPts = pts
                }
                c2f.release()
                approx.release()
                c.release()
            }

            val quad = bestPts ?: return null
            val ordered = orderCorners(quad)

            // ৬) আগের ফ্রেমের সাথে কোণার নম্বর মেলাই ও মসৃণ করি
            val cur = FloatArray(8)
            for (i in 0 until 4) {
                cur[i * 2] = (ordered[i].x / rw).toFloat()
                cur[i * 2 + 1] = (ordered[i].y / rh).toFloat()
            }
            val p = prev
            val result: FloatArray
            if (p == null) {
                result = cur
            } else {
                var bestShift = 0
                var bestDist = Float.MAX_VALUE
                for (s in 0 until 4) {
                    var d = 0f
                    for (i in 0 until 4) {
                        val j = (i + s) % 4
                        d += abs(cur[j * 2] - p[i * 2]) + abs(cur[j * 2 + 1] - p[i * 2 + 1])
                    }
                    if (d < bestDist) {
                        bestDist = d
                        bestShift = s
                    }
                }
                val aligned = FloatArray(8)
                for (i in 0 until 4) {
                    val j = (i + bestShift) % 4
                    aligned[i * 2] = cur[j * 2]
                    aligned[i * 2 + 1] = cur[j * 2 + 1]
                }
                result = if (bestDist > 0.8f) {
                    aligned // অনেক লাফ দিয়েছে, মসৃণ না করে সরাসরি নিই
                } else {
                    FloatArray(8) { k -> p[k] * 0.4f + aligned[k] * 0.6f }
                }
            }
            prev = result
            return Result(result, rw.toFloat() / rh)
        } finally {
            mats.forEach { it.release() }
        }
    }

    /** চার কোণাকে ক্রমে সাজাই: উপর-বাম, উপর-ডান, নিচ-ডান, নিচ-বাম */
    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val tr = pts.maxByOrNull { it.x - it.y }!!
        val bl = pts.minByOrNull { it.x - it.y }!!
        return arrayOf(tl, tr, br, bl)
    }
}
