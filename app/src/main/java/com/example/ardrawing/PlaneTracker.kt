package com.example.ardrawing

import androidx.camera.core.ImageProxy
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs

object Frames {
    fun uprightGray(image: ImageProxy): Mat {
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
        val gray = Mat(h, w, CvType.CV_8UC1)
        gray.put(0, 0, data)

        val small = Mat()
        Imgproc.resize(gray, small, Size(480.0, h * 480.0 / w))
        gray.release()

        val up = Mat()
        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(small, up, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(small, up, Core.ROTATE_180)
            270 -> Core.rotate(small, up, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> small.copyTo(up)
        }
        small.release()
        return up
    }
}

class PlaneTracker {

    class Out(
        val h: FloatArray,
        val rw: Int,
        val rh: Int,
        val good: Boolean
    )

    private var prev: Mat? = null
    private val total = DoubleArray(9)
    private var locked = false

    init {
        setIdentity()
    }

    private fun setIdentity() {
        for (i in 0 until 9) total[i] = if (i % 4 == 0) 1.0 else 0.0
    }

    @Synchronized
    fun startLock() {
        setIdentity()
        locked = true
    }

    @Synchronized
    fun stopLock() {
        setIdentity()
        locked = false
    }

    @Synchronized
    fun process(image: ImageProxy): Out {
        val cur = Frames.uprightGray(image)
        var good = true
        val p = prev
        if (locked) {
            good = p != null && p.size() == cur.size() && track(p, cur)
        }
        p?.release()
        prev = cur
        return Out(FloatArray(9) { total[it].toFloat() }, cur.cols(), cur.rows(), good)
    }

    private fun track(prevFrame: Mat, cur: Mat): Boolean {
        val corners = MatOfPoint()
        val p0 = MatOfPoint2f()
        val p1 = MatOfPoint2f()
        val st = MatOfByte()
        val err = MatOfFloat()
        val mask = Mat()
        val src = MatOfPoint2f()
        val dst = MatOfPoint2f()
        try {
            Imgproc.goodFeaturesToTrack(prevFrame, corners, 250, 0.01, 8.0)
            if (corners.rows() < 12) return false
            p0.fromArray(*corners.toArray())
            Video.calcOpticalFlowPyrLK(prevFrame, cur, p0, p1, st, err)

            val a0 = p0.toArray()
            val a1 = p1.toArray()
            val s = st.toArray()
            val g0 = ArrayList<Point>()
            val g1 = ArrayList<Point>()
            for (i in a0.indices) {
                if (i < a1.size && i < s.size && s[i].toInt() == 1) {
                    g0.add(a0[i])
                    g1.add(a1[i])
                }
            }
            if (g0.size < 12) return false

            src.fromList(g0)
            dst.fromList(g1)
            val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, mask)
            if (h.empty()) {
                h.release()
                return false
            }
            val inliers = Core.countNonZero(mask)
            val hd = DoubleArray(9)
            h.get(0, 0, hd)
            h.release()
            if (inliers < 10) return false

            val nt = DoubleArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    var sum = 0.0
                    for (k in 0 until 3) sum += hd[r * 3 + k] * total[k * 3 + c]
                    nt[r * 3 + c] = sum
                }
            }
            val w = nt[8]
            if (abs(w) < 1e-9) return false
            for (i in 0 until 9) total[i] = nt[i] / w
            return true
        } finally {
            corners.release()
            p0.release()
            p1.release()
            st.release()
            err.release()
            mask.release()
            src.release()
            dst.release()
        }
    }
}
