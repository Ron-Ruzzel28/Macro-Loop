package com.macroloop.app

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a saved picture on a screen frame.
 * Grayscale template matching (normalised correlation), which ignores overall brightness changes.
 * Large search areas use a half-size pass first, then refine at full size.
 * Optionally tries a few slightly bigger and smaller copies of the picture.
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
        val ms: Long = 0,
        val sizeFactor: Double = 1.0,
        /** Score of the weakest detailed part of the picture (1.0 if not checked). */
        val weakestPart: Double = 1.0,
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
    private val FLEX_FACTORS = doubleArrayOf(1.0, 0.94, 1.06, 0.88, 1.12)

    /** Each detailed part of the picture may score this much lower than the whole picture needs. */
    private const val PART_SLACK = 0.15

    /** If the half-size pass scores below this share of the limit, the picture is not there: skip the slow pass. */
    private const val COARSE_GIVE_UP = 0.6

    @Volatile
    private var loaded = false

    private var cacheBmp: Bitmap? = null
    private var cacheGray: Mat? = null

    /** The grey copy of a frame. Many checks look at the same frame, so it is made once. Not released here: the garbage collector does that. */
    @Synchronized
    private fun grayCached(b: Bitmap): Mat {
        val c = cacheGray
        if (c != null && cacheBmp === b) return c
        val g = gray(b)
        cacheBmp = b
        cacheGray = g
        return g
    }

    fun ensureLoaded(): Boolean {
        if (!loaded) loaded = OpenCVLoader.initLocal()
        return loaded
    }

    /**
     * Finds the best match.
     * @param region where to look, in frame pixels. null = the whole frame.
     * @param scale how much to resize the picture first (frame width / width when it was picked).
     * @param flexScale also try slightly bigger and smaller copies of the picture.
     */
    fun find(
        frame: Bitmap,
        template: Bitmap,
        region: android.graphics.Rect?,
        thresholdPct: Int,
        scale: Double = 1.0,
        flexScale: Boolean = false,
    ): Result {
        if (!ensureLoaded()) return Result(false, 0.0, 0, 0, 0, 0)
        val started = System.nanoTime()
        val f = grayCached(frame)
        val base = gray(template)
        try {
            val limit = thresholdPct / 100.0
            val first = findOnce(f, base, scale, region, thresholdPct, gate = true)
            if (first.tooPlain || first.found || !flexScale) return first.copy(ms = elapsedMs(started))

            // Size tolerance: try a few sizes by score alone, refine around the best, then check the parts once.
            var best = first
            for (k in FLEX_FACTORS.drop(1)) {
                val r = findOnce(f, base, scale * k, region, thresholdPct, gate = false).copy(sizeFactor = k)
                if (r.score > best.score) best = r
            }
            if (best.score >= limit * 0.7) {
                val centre = best.sizeFactor
                for (d in doubleArrayOf(-0.03, -0.015, 0.015, 0.03)) {
                    val k = centre + d
                    val r = findOnce(f, base, scale * k, region, thresholdPct, gate = false).copy(sizeFactor = k)
                    if (r.score > best.score) best = r
                }
            }
            val final = findOnce(f, base, scale * best.sizeFactor, region, thresholdPct, gate = true)
            return final.copy(sizeFactor = best.sizeFactor, ms = elapsedMs(started))
        } finally {
            base.release()
        }
    }

    /**
     * Finds every match above the threshold, sorted top to bottom, then left to right.
     * Matches are kept apart by at least half the picture's size.
     */
    fun findAll(
        frame: Bitmap,
        template: Bitmap,
        region: android.graphics.Rect?,
        thresholdPct: Int,
        scale: Double = 1.0,
        maxMatches: Int = 20,
    ): List<Result> {
        if (!ensureLoaded()) return emptyList()
        val started = System.nanoTime()
        val f = grayCached(frame)
        var t = gray(template)
        try {
            if (abs(scale - 1.0) > 0.02) t = resized(t, scale)
            if (t.cols() < 6 || t.rows() < 6 || isPlain(t)) return emptyList()
            val (rx, ry, rw, rh) = clampRegion(f, t, region)
            val sub = f.submat(Rect(rx, ry, rw, rh))
            if (rw < t.cols() || rh < t.rows()) return emptyList()
            val res = Mat()
            Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
            val threshold = thresholdPct / 100.0
            val out = ArrayList<Result>()
            val halfW = max(1, t.cols() / 2)
            val halfH = max(1, t.rows() / 2)
            while (out.size < maxMatches) {
                val mm = Core.minMaxLoc(res)
                val s = if (mm.maxVal.isNaN()) 0.0 else mm.maxVal
                if (s < threshold) break
                val weakest = weakestPart(sub, t, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt())
                if (weakest >= threshold - PART_SLACK) {
                    out.add(Result(true, s, rx + mm.maxLoc.x.toInt(), ry + mm.maxLoc.y.toInt(), t.cols(), t.rows(), weakestPart = weakest))
                }
                Imgproc.rectangle(
                    res,
                    Point(mm.maxLoc.x - halfW, mm.maxLoc.y - halfH),
                    Point(mm.maxLoc.x + halfW, mm.maxLoc.y + halfH),
                    Scalar(-1.0),
                    -1,
                )
            }
            res.release()
            val ms = elapsedMs(started)
            val rowHeight = max(1, t.rows() / 2)
            return out.sortedWith(compareBy({ it.y / rowHeight }, { it.x })).map { it.copy(ms = ms) }
        } finally {
            t.release()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun resized(t: Mat, scale: Double): Mat {
        val out = Mat()
        Imgproc.resize(t, out, Size(), scale, scale, if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        t.release()
        return out
    }

    private fun isPlain(t: Mat): Boolean {
        val std = MatOfDouble()
        Core.meanStdDev(t, MatOfDouble(), std)
        return std.get(0, 0)[0] < 4.0
    }

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun clampRegion(f: Mat, t: Mat, region: android.graphics.Rect?): Box {
        if (region == null) return Box(0, 0, f.cols(), f.rows())
        val rx = region.left.coerceIn(0, f.cols() - 1)
        val ry = region.top.coerceIn(0, f.rows() - 1)
        val rw = min(region.width(), f.cols() - rx)
        val rh = min(region.height(), f.rows() - ry)
        // A region smaller than the picture cannot hold it: look everywhere instead.
        return if (rw < t.cols() || rh < t.rows()) Box(0, 0, f.cols(), f.rows()) else Box(rx, ry, rw, rh)
    }

    private fun findOnce(f: Mat, base: Mat, scale: Double, region: android.graphics.Rect?, thresholdPct: Int, gate: Boolean): Result {
        var t = base.clone()
        try {
            if (abs(scale - 1.0) > 0.02) t = resized(t, scale)
            if (t.cols() < 6 || t.rows() < 6) return Result(false, 0.0, 0, 0, t.cols(), t.rows(), tooPlain = true)
            if (isPlain(t)) return Result(false, 0.0, 0, 0, t.cols(), t.rows(), tooPlain = true)

            val (rx, ry, rw, rh) = clampRegion(f, t, region)
            val sub = f.submat(Rect(rx, ry, rw, rh))
            if (sub.cols() < t.cols() || sub.rows() < t.rows()) return Result(false, 0.0, 0, 0, t.cols(), t.rows())

            val limit = thresholdPct / 100.0
            var best: Pair<Point, Double>? = null
            if (rw.toDouble() * rh > COARSE_LIMIT_PIXELS) best = coarseThenFine(sub, t)
            val giveUp = best != null && best.second < limit * COARSE_GIVE_UP
            if (!giveUp && (best == null || best.second < limit)) best = match(sub, t)
            val score = best?.second ?: 0.0
            val loc = best?.first ?: Point(0.0, 0.0)
            var weakest = 1.0
            var found = score >= limit
            if (found && gate) {
                weakest = weakestPart(sub, t, loc.x.toInt(), loc.y.toInt())
                if (weakest < limit - PART_SLACK) found = false
            }
            return Result(
                found = found,
                score = score,
                x = rx + loc.x.toInt(),
                y = ry + loc.y.toInt(),
                w = t.cols(),
                h = t.rows(),
                weakestPart = weakest,
            )
        } finally {
            t.release()
        }
    }

    /**
     * Splits the picture into a grid and scores each detailed part at the match position.
     * A button with one different word scores high as a whole (most of it is the same colour),
     * but the part holding the word scores low. Returns the lowest part score.
     */
    private fun weakestPart(area: Mat, t: Mat, x: Int, y: Int): Double {
        val n = when {
            t.cols() >= 60 && t.rows() >= 60 -> 3
            t.cols() >= 36 && t.rows() >= 36 -> 2
            else -> return 1.0
        }
        if (x < 0 || y < 0 || x + t.cols() > area.cols() || y + t.rows() > area.rows()) return 1.0
        var lowest = 1.0
        val tw = t.cols() / n
        val th = t.rows() / n
        val std = MatOfDouble()
        val res = Mat()
        for (gy in 0 until n) {
            for (gx in 0 until n) {
                val r = Rect(gx * tw, gy * th, tw, th)
                val tile = t.submat(r)
                Core.meanStdDev(tile, MatOfDouble(), std)
                if (std.get(0, 0)[0] < 6.0) continue
                val patch = area.submat(Rect(x + r.x, y + r.y, tw, th))
                Imgproc.matchTemplate(patch, tile, res, Imgproc.TM_CCOEFF_NORMED)
                val v = res.get(0, 0)[0]
                lowest = min(lowest, if (v.isNaN()) 0.0 else v)
            }
        }
        res.release()
        return lowest
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
