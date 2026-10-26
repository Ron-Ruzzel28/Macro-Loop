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

    /** True when enough of the area is within the tolerance of the colour. A box needs more of its area than the whole screen. */
    fun colorPresent(frame: Bitmap, r: Rect, color: Int, tolerancePct: Int, wholeScreen: Boolean): Boolean {
        val need = if (wholeScreen) 0.004 else 0.30
        var hit = 0; var n = 0
        val stepX = maxOf(1, r.width() / 120)
        val stepY = maxOf(1, r.height() / 120)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                if (distancePct(frame.getPixel(x, y), color) <= tolerancePct) hit++
                n++
                x += stepX
            }
            y += stepY
        }
        return n > 0 && hit.toDouble() / n >= need
    }

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
        out.add(0, "Screen checks: $passed of $total")
        return out
    }
}
