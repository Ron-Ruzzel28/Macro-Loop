package com.macroloop.app

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a saved picture on a screen frame.
 * Grayscale template matching (normalised correlation), which ignores overall brightness changes.
 * Large search areas use a half-size pass first, then refine at full size.
 */
object Matcher {

    /** x, y = top-left of the match in frame pixels. score is 0.0 to 1.0. */
    data class Result(
        val found: Boolean,
        val score: Double,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val tooPlain: Boolean = false,
    ) {
        val centerX get() = x + w / 2
        val centerY get() = y + h / 2
    }

    /** The area "near where I picked it": the picked box plus one box-size of margin on every side. */
    fun nearRegion(boxX: Int, boxY: Int, boxW: Int, boxH: Int, scale: Double, frameW: Int, frameH: Int): android.graphics.Rect {
        val x = (boxX * scale).toInt()
        val y = (boxY * scale).toInt()
        val w = (boxW * scale).toInt()
        val h = (boxH * scale).toInt()
        val pad = max(w, h)
        return android.graphics.Rect(
            max(0, x - pad), max(0, y - pad),
            min(frameW, x + w + pad), min(frameH, y + h + pad),
        )
    }

    private const val COARSE_LIMIT_PIXELS = 400_000.0

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (!loaded) loaded = OpenCVLoader.initLocal()
        return loaded
    }

    /**
     * @param region where to look, in frame pixels. null = the whole frame.
     * @param scale how much to resize the picture first (frame width / width when it was picked).
     */
    fun find(
        frame: Bitmap,
        template: Bitmap,
        region: android.graphics.Rect?,
        thresholdPct: Int,
        scale: Double = 1.0,
    ): Result {
        if (!ensureLoaded()) return Result(false, 0.0, 0, 0, 0, 0)
        val f = gray(frame)
        var t = gray(template)
        try {
            if (abs(scale - 1.0) > 0.02) {
                val resized = Mat()
                Imgproc.resize(t, resized, Size(), scale, scale, Imgproc.INTER_AREA)
                t.release()
                t = resized
            }
            if (t.cols() < 6 || t.rows() < 6) return Result(false, 0.0, 0, 0, t.cols(), t.rows(), tooPlain = true)

            val std = MatOfDouble()
            Core.meanStdDev(t, MatOfDouble(), std)
            if (std.get(0, 0)[0] < 4.0) return Result(false, 0.0, 0, 0, t.cols(), t.rows(), tooPlain = true)

            var rx = 0
            var ry = 0
            var rw = f.cols()
            var rh = f.rows()
            if (region != null) {
                rx = region.left.coerceIn(0, f.cols() - 1)
                ry = region.top.coerceIn(0, f.rows() - 1)
                rw = min(region.width(), f.cols() - rx)
                rh = min(region.height(), f.rows() - ry)
                if (rw < t.cols() || rh < t.rows()) {
                    rx = 0; ry = 0; rw = f.cols(); rh = f.rows()
                }
            }
            val sub = f.submat(Rect(rx, ry, rw, rh))

            var best: Pair<Point, Double>? = null
            if (rw.toDouble() * rh > COARSE_LIMIT_PIXELS) {
                best = coarseThenFine(sub, t)
            }
            if (best == null || best.second < thresholdPct / 100.0) {
                // Direct full-size search. Slower, but exact.
                best = match(sub, t)
            }
            val score = best.second
            return Result(
                found = score >= thresholdPct / 100.0,
                score = score,
                x = rx + best.first.x.toInt(),
                y = ry + best.first.y.toInt(),
                w = t.cols(),
                h = t.rows(),
            )
        } finally {
            f.release()
            t.release()
        }
    }

    private fun gray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return g
    }

    private fun match(area: Mat, t: Mat): Pair<Point, Double> {
        if (area.cols() < t.cols() || area.rows() < t.rows()) return Point(0.0, 0.0) to 0.0
        val res = Mat()
        Imgproc.matchTemplate(area, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        val s = if (mm.maxVal.isNaN()) 0.0 else mm.maxVal
        return mm.maxLoc to s
    }

    private fun coarseThenFine(area: Mat, t: Mat): Pair<Point, Double>? {
        val small = Mat()
        val tSmall = Mat()
        try {
            Imgproc.resize(area, small, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
            Imgproc.resize(t, tSmall, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
            if (tSmall.cols() < 8 || tSmall.rows() < 8) return null
            val (loc, _) = match(small, tSmall)
            val pad = 12
            val wx = max(0, (loc.x * 2).toInt() - pad)
            val wy = max(0, (loc.y * 2).toInt() - pad)
            val ww = min(area.cols() - wx, t.cols() + 2 * pad)
            val wh = min(area.rows() - wy, t.rows() + 2 * pad)
            if (ww < t.cols() || wh < t.rows()) return null
            val win = area.submat(Rect(wx, wy, ww, wh))
            val (l2, s2) = match(win, t)
            return Point(wx + l2.x, wy + l2.y) to s2
        } finally {
            small.release()
            tSmall.release()
        }
    }
}
