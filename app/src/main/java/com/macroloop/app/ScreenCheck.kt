package com.macroloop.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Looking at the screen for a colour, or for words. Pictures are found by [Matcher].
 *
 * Colour: the colour you picked appears when enough of the area is within the tolerance of it.
 * Words: Google's on-device reader (no internet) reads the area, and the words are found when the text you typed is inside.
 */
object ScreenCheck {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The saved box, moved to this frame's size (the phone may have been turned or the size may differ a little). */
    fun regionFor(c: Condition, frame: Bitmap): Rect {
        if (c.wholeScreen || c.boxW <= 0) return Rect(0, 0, frame.width, frame.height)
        val sx = if (c.capW > 0) frame.width.toDouble() / c.capW else 1.0
        val sy = if (c.capH > 0) frame.height.toDouble() / c.capH else sx
        val l = (c.boxX * sx).toInt().coerceIn(0, frame.width - 2)
        val t = (c.boxY * sy).toInt().coerceIn(0, frame.height - 2)
        val r = ((c.boxX + c.boxW) * sx).toInt().coerceIn(l + 2, frame.width)
        val b = ((c.boxY + c.boxH) * sy).toInt().coerceIn(t + 2, frame.height)
        return Rect(l, t, r, b)
    }

    /** Distance between two colours, 0 (same) to 100 (black against white). */
    fun distancePct(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return (Math.sqrt((dr * dr + dg * dg + db * db).toDouble()) / 441.67 * 100).toInt()
    }

    /** The average colour of an area. */
    fun meanColor(frame: Bitmap, r: Rect): Int {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val stepX = maxOf(1, r.width() / 40)
        val stepY = maxOf(1, r.height() / 40)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val p = frame.getPixel(x, y)
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255; n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return 0
        return (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
    }

    /** How much of the area must be the colour (a fraction). [minPct] 0 = the standard rule: 0.2% of the screen, or 5% of a box. */
    fun neededShare(minPct: Double, wholeScreen: Boolean): Double =
        if (minPct > 0.0) minPct / 100.0 else if (wholeScreen) 0.002 else 0.05

    /** "0.2%" style text for a fraction. */
    fun pctText(share: Double): String {
        val p = share * 100.0
        return if (p >= 10) "%.0f%%".format(p) else if (p >= 1) "%.1f%%".format(p) else "%.2f%%".format(p)
    }

    /**
     * The share (0.0 to 1.0) of the area that is within the tolerance of the colour. Every pixel counts for small areas;
     * big areas look at every 2nd or 3rd pixel, which still sees a spot 10 pixels wide.
     */
    fun colorShare(frame: Bitmap, r: Rect, color: Int, tolerancePct: Int): Double {
        val w = r.width(); val h = r.height()
        if (w <= 0 || h <= 0) return 0.0
        val stride = maxOf(1, Math.ceil(Math.sqrt(w.toDouble() * h / 1_200_000.0)).toInt())
        val row = IntArray(w)
        var hit = 0L; var n = 0L
        var y = r.top
        while (y < r.bottom) {
            frame.getPixels(row, 0, w, r.left, y, w, 1)
            var x = 0
            while (x < w) {
                if (distancePct(row[x], color) <= tolerancePct) hit++
                n++
                x += stride
            }
            y += stride
        }
        return if (n == 0L) 0.0 else hit.toDouble() / n
    }

    /** True when enough of the area is within the tolerance of the colour. */
    fun colorPresent(frame: Bitmap, r: Rect, color: Int, tolerancePct: Int, wholeScreen: Boolean, minPct: Double = 0.0): Boolean =
        colorShare(frame, r, color, tolerancePct) >= neededShare(minPct, wholeScreen)

    /** Reads the words in an area. Blocking: call it off the main thread. */
    fun readText(frame: Bitmap, r: Rect): String {
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        return try {
            Tasks.await(reader.process(InputImage.fromBitmap(crop, 0))).text
        } catch (e: Exception) {
            ""
        } finally {
            if (crop !== frame) crop.recycle()
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** True when the wanted words are in what was read (letters and numbers only, any case). */
    fun textMatches(read: String, wanted: String): Boolean {
        val w = norm(wanted)
        return w.isNotEmpty() && norm(read).contains(w)
    }

    /** True when the whole picture is one flat colour: a screen that was not ready yet, not a real screen. */
    fun isBlank(frame: Bitmap): Boolean {
        var lo = 255; var hi = 0
        for (iy in 1..12) for (ix in 1..8) {
            val p = frame.getPixel(frame.width * ix / 9, frame.height * iy / 13)
            val v = maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
            lo = minOf(lo, v); hi = maxOf(hi, v)
            if (hi - lo > 6) return false
        }
        return true
    }

    /**
     * True when the capture box itself is in the picture: its border is a solid line of one colour along all
     * four edges of the area. A real screen almost never has that.
     */
    fun markerShows(frame: Bitmap, region: Rect): Boolean {
        val x0 = region.left.coerceIn(0, frame.width - 1)
        val y0 = region.top.coerceIn(0, frame.height - 1)
        val x1 = (region.right - 1).coerceIn(x0, frame.width - 1)
        val y1 = (region.bottom - 1).coerceIn(y0, frame.height - 1)
        if (x1 - x0 < 6 || y1 - y0 < 6) return false
        val inset = 2
        fun accent(p: Int): Boolean {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val m = TokenInts.primary
            return kotlin.math.abs(r - ((m shr 16) and 255)) < 28 && kotlin.math.abs(g - ((m shr 8) and 255)) < 28 && kotlin.math.abs(b - (m and 255)) < 28
        }
        fun share(count: Int, get: (Int) -> Int): Float {
            var hit = 0
            for (i in 0 until count) if (accent(get(i))) hit++
            return hit.toFloat() / count
        }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val top = share(w) { frame.getPixel(x0 + it, y0 + inset) }
        val bottom = share(w) { frame.getPixel(x0 + it, y1 - inset) }
        val left = share(h) { frame.getPixel(x0 + inset, y0 + it) }
        val right = share(h) { frame.getPixel(x1 - inset, y0 + it) }
        return listOf(top, bottom, left, right).count { it > 0.6f } >= 3
    }

    fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    fun colorChanged(base: Int, now: Int, tolerancePct: Int) = abs(distancePct(base, now)) > tolerancePct

    /** Checks colour and text finding on made-up screens. */
    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        var passed = 0
        var total = 0
        fun check(name: String, ok: Boolean) {
            total++
            if (ok) passed++ else out += "FAIL  screen check: $name"
        }
        val red = 0xFFE02020.toInt()
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(0xFFFFFFFF.toInt())
        cv.drawRect(100f, 100f, 200f, 200f, android.graphics.Paint().apply { color = red })
        val all = Rect(0, 0, 300, 300)
        val box = Rect(100, 100, 200, 200)
        check("white against black is 100", distancePct(0xFFFFFFFF.toInt(), 0xFF000000.toInt()) == 100)
        check("the same colour is 0", distancePct(red, red) == 0)
        check("mean colour of the red box", distancePct(meanColor(bmp, box), red) < 3)
        check("red found on the whole screen", colorPresent(bmp, all, red, 20, true))
        check("blue not found", !colorPresent(bmp, all, 0xFF2020E0.toInt(), 20, true))
        check("red fills the box area", colorPresent(bmp, box, red, 20, false))
        check("red does not fill the white corner", !colorPresent(bmp, Rect(0, 0, 90, 90), red, 20, false))
        check("a changed colour is noticed", colorChanged(red, 0xFF20E020.toInt(), 12))
        check("a near colour is not a change", !colorChanged(red, 0xFFE42222.toInt(), 12))
        val words = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        val wc = android.graphics.Canvas(words)
        wc.drawColor(0xFFFFFFFF.toInt())
        wc.drawText("CLAIM REWARD 42", 20f, 120f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); textSize = 64f })
        val read = readText(words, Rect(0, 0, 600, 200))
        check("text is read (\"$read\")", textMatches(read, "claim reward"))
        check("other words are not found", !textMatches(read, "level up"))
        check("a flat white picture is blank", isBlank(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFFFFFFF.toInt()) }))
        check("a real screen is not blank", !isBlank(bmp))
        val withBox = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val boxCv = android.graphics.Canvas(withBox)
        val line = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 8f; color = TokenInts.primary }
        boxCv.drawRect(60f + 4f, 60f + 4f, 220f - 4f, 160f - 4f, line)
        check("capture box still on screen is noticed", markerShows(withBox, Rect(60, 60, 220, 160)))
        check("clean screen has no capture box", !markerShows(bmp, Rect(60, 60, 220, 160)))
        // a small coloured icon on a big screen (the case that used to be missed)
        val big = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        big.eraseColor(0xFF202020.toInt())
        android.graphics.Canvas(big).drawRect(500f, 1200f, 540f, 1240f, android.graphics.Paint().apply { color = red })
        val bigAll = Rect(0, 0, 1080, 2400)
        check("small icon found with a small amount (0.02%)", colorPresent(big, bigAll, red, 20, true, 0.02))
        check("small icon not found when much is asked (2%)", !colorPresent(big, bigAll, red, 20, true, 2.0))
        check("share of a 40x40 spot is about 0.06%", colorShare(big, bigAll, red, 20) in 0.0004..0.0008)
        out.add(0, "Screen checks: $passed of $total")
        return out
    }
}
