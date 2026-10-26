package com.macroloop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Checks the picture finder against cases with known answers: the same picture exactly, a
 * brighter or darker screen, noise, a different screen size, a missing picture, and several
 * copies. Also measures speed. Uses a drawn screen so the results are the same every time.
 * Results are shown on the Picture test screen and written to the log tag "MacroLoopSelfTest".
 */
object SelfTest {
    private const val TAG = "MacroLoopSelfTest"
    private const val W = 1080
    private const val H = 2436

    var report by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set

    private class Case(val name: String, val expect: String, val ok: Boolean, val detail: String)

    fun run(dir: File) {
        if (running) return
        running = true
        report = "Running..."
        val lines = ArrayList<String>()
        try {
            if (!Matcher.ensureLoaded()) {
                report = "The picture finder could not start."
                return
            }
            val cases = ArrayList<Case>()
            val frame = drawScreen(seed = 7)
            val box = android.graphics.Rect(560, 1700, 560 + 380, 1700 + 130)   // the "Claim" button
            val template = Bitmap.createBitmap(frame, box.left, box.top, box.width(), box.height())
            val near = Matcher.nearRegion(box.left, box.top, box.width(), box.height(), 1.0, W, H)

            // 1. exact
            val exact = Matcher.find(frame, template, near, 90)
            cases += Case("Same picture, same screen", "found, in the right place", exact.found && close(exact, box), desc(exact))

            // 2. brightness
            for ((name, gain, bias) in listOf(Triple("Screen 25% brighter", 1.25f, 0f), Triple("Screen 25% darker", 0.75f, 0f), Triple("Night mode tint", 0.8f, 18f))) {
                val r = Matcher.find(adjust(frame, gain, bias), template, near, 90)
                cases += Case(name, "found, in the right place", r.found && close(r, box), desc(r))
            }

            // 3. noise
            for (sigma in listOf(6, 14)) {
                val r = Matcher.find(addNoise(frame, sigma, 11), template, near, 85)
                cases += Case("Noise (strength $sigma), needs 85%", "found, in the right place", r.found && close(r, box, 3), desc(r))
            }

            // 4. a different screen size, told the scale
            for (k in listOf(0.9, 1.1)) {
                val small = Bitmap.createScaledBitmap(frame, (W * k).toInt(), (H * k).toInt(), true)
                val region = Matcher.nearRegion(box.left, box.top, box.width(), box.height(), k, small.width, small.height)
                val r = Matcher.find(small, template, region, 88, scale = k)
                val want = android.graphics.Rect((box.left * k).toInt(), (box.top * k).toInt(), (box.right * k).toInt(), (box.bottom * k).toInt())
                cases += Case("Screen size x$k, scale known, needs 88%", "found, in the right place", r.found && close(r, want, 4), desc(r))
            }

            // 5. picture slightly different size, scale NOT told
            val big = Bitmap.createScaledBitmap(frame, (W * 1.07).toInt(), (H * 1.07).toInt(), true)
            val bigRegion = Matcher.nearRegion(box.left, box.top, box.width(), box.height(), 1.07, big.width, big.height)
            val fixed = Matcher.find(big, template, bigRegion, 90, flexScale = false)
            val flex = Matcher.find(big, template, bigRegion, 90, flexScale = true)
            cases += Case("7% bigger, size tolerance OFF", "info", true, desc(fixed) + " (score only)")
            cases += Case("7% bigger, size tolerance ON", "found", flex.found, desc(flex))

            // 6. picture missing
            val gone = drawScreen(seed = 7, hideButton = true)
            val missing = Matcher.find(gone, template, near, 90)
            cases += Case("Picture not on screen", "not found", !missing.found, desc(missing))

            // 7. look-alike (same button, different word)
            val lookalike = drawScreen(seed = 7, buttonWord = "Claim all")
            val look = Matcher.find(lookalike, template, near, 90)
            cases += Case("Look-alike (different word)", "not found at 90%", !look.found, desc(look))
            val look2 = Matcher.find(lookalike, template, near, 80)
            cases += Case("Look-alike, even at a loose 80%", "not found", !look2.found, desc(look2))

            // 8. a too-plain picture
            val plain = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(40, 40, 60)) }
            val plainRes = Matcher.find(frame, plain, null, 90)
            cases += Case("Plain picture", "refused as too plain", plainRes.tooPlain, desc(plainRes))

            // 9. several copies
            val multi = drawScreen(seed = 7, copies = 3)
            val all = Matcher.findAll(multi, template, null, 90)
            cases += Case("Three copies on screen", "3 matches", all.size == 3, "${all.size} matches: " + all.joinToString { "(${it.x},${it.y}) ${pct(it.score)}" })

            // 10. speed
            val nearMs = timeIt(5) { Matcher.find(frame, template, near, 90) }
            val wholeMs = timeIt(5) { Matcher.find(frame, template, null, 90) }
            val flexMs = timeIt(3) { Matcher.find(big, template, null, 99, flexScale = true) }
            val allMs = timeIt(3) { Matcher.findAll(multi, template, null, 90) }
            lines += "Speed on this phone (1080x2436 screen, ${box.width()}x${box.height()} picture):"
            lines += "  near the picture:         ${nearMs} ms"
            lines += "  whole screen:             ${wholeMs} ms"
            lines += "  whole screen, size flex:  ${flexMs} ms (worst case, 5 sizes)"
            lines += "  every match, whole screen: ${allMs} ms"
            lines += ""

            val failed = cases.count { !it.ok }
            lines += "Accuracy: ${cases.size - failed} of ${cases.size} cases passed"
            for (c in cases) lines += "${if (c.ok) "PASS" else "FAIL"}  ${c.name}\n      expected: ${c.expect}\n      got: ${c.detail}"
            lines += ""
            lines += LogicTest.run()
            report = lines.joinToString("\n")
        } catch (e: Throwable) {
            report = "Self-test crashed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            running = false
            Log.i(TAG, "\n$report")
            runCatching { File(dir, "selftest.txt").writeText(report) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun pct(s: Double) = "${(s * 100).toInt()}%"

    private fun desc(r: Matcher.Result) =
        (if (r.tooPlain) "too plain" else "${if (r.found) "found" else "not found"} ${pct(r.score)} (weakest part ${pct(r.weakestPart)}) at (${r.x},${r.y}) in ${r.ms} ms")

    private fun close(r: Matcher.Result, box: android.graphics.Rect, tol: Int = 2) =
        abs(r.x - box.left) <= tol && abs(r.y - box.top) <= tol

    private inline fun timeIt(times: Int, block: () -> Unit): Long {
        block() // warm up
        val t = System.nanoTime()
        repeat(times) { block() }
        return (System.nanoTime() - t) / 1_000_000 / times
    }

    /** Draws something like a phone game screen: gradient, panels, icons, text and one "Claim" button. */
    private fun drawScreen(seed: Int, hideButton: Boolean = false, buttonWord: String = "Claim", copies: Int = 1): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.rgb(30, 40, 90), Color.rgb(90, 50, 110), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null
        // panels, icons and text lines
        repeat(26) {
            p.color = Color.argb(160, rnd.nextInt(40, 220), rnd.nextInt(40, 220), rnd.nextInt(40, 220))
            val x = rnd.nextInt(20, 760).toFloat()
            val y = rnd.nextInt(60, 2300).toFloat()
            c.drawRoundRect(RectF(x, y, x + rnd.nextInt(120, 320), y + rnd.nextInt(80, 260)), 24f, 24f, p)
        }
        p.color = Color.WHITE
        p.textSize = 44f
        repeat(18) { c.drawText("Quest ${rnd.nextInt(10, 99)}  reward x${rnd.nextInt(2, 90)}", rnd.nextInt(30, 600).toFloat(), 120f + it * 120f, p) }
        // the button we look for
        if (!hideButton) {
            val spots = if (copies == 1) listOf(560 to 1700) else listOf(560 to 1700, 60 to 400, 600 to 900).take(copies)
            for ((bx, by) in spots) {
                p.color = Color.rgb(250, 190, 30)
                c.drawRoundRect(RectF(bx.toFloat(), by.toFloat(), bx + 380f, by + 130f), 36f, 36f, p)
                p.color = Color.rgb(120, 70, 0)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                c.drawRoundRect(RectF(bx + 6f, by + 6f, bx + 374f, by + 124f), 32f, 32f, p)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(60, 30, 0)
                p.textSize = 58f
                p.isFakeBoldText = true
                c.drawText(buttonWord, bx + 42f, by + 85f, p)
                p.isFakeBoldText = false
            }
        }
        return bmp
    }

    private fun adjust(src: Bitmap, gain: Float, bias: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m = ColorMatrix(floatArrayOf(gain, 0f, 0f, 0f, bias, 0f, gain, 0f, 0f, bias, 0f, 0f, gain, 0f, bias, 0f, 0f, 0f, 1f, 0f))
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun addNoise(src: Bitmap, sigma: Int, seed: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = Random(seed)
        for (i in px.indices) {
            val n = ((rnd.nextInt(-100, 101) + rnd.nextInt(-100, 101)) * sigma / 100)
            val c = px[i]
            val r = (Color.red(c) + n).coerceIn(0, 255)
            val g = (Color.green(c) + n).coerceIn(0, 255)
            val b = (Color.blue(c) + n).coerceIn(0, 255)
            px[i] = Color.rgb(r, g, b)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
