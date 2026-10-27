package com.macroloop.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

private typealias Path = List<Pair<Int, Boolean>>

private data class Ref(val path: Path, val index: Int)

/** Where a new step goes: into the list at [path], at [insertAt] (or the end), or in place of the step at [replace]. */
private data class Target(val path: Path, val insertAt: Int? = null, val replace: Int? = null)

private class Row(val text: String, val color: Int = TokenInts.text, val icon: Icon? = null, val help: Boolean = true, val action: (() -> Unit)? = null)

/**
 * The editor, built the way Macrorify's Abstract editor works:
 *  1. a small round button (the hand) over any app. Tap it for a menu: Manage Actions, Run Test, Save, More, Exit;
 *  2. Manage Actions opens the list panel: shrink, name, play, plus, then one card per step;
 *  3. tap a card for its menu; tap a picture row for that picture's menu;
 *  4. the plus button lists every action; a new picture is placed with a box and an X Y W H panel.
 * The differences are the ones asked for: Go to links, names, tests from a step, reordering, a movable capture panel.
 */
class EditorOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    macro: Macro,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val macroId = macro.id
    private val macroName = macro.name
    private val repeat = macro.repeat
    private val game = macro.game
    private val version = macro.version
    private val base = macro
    private var mainSteps: List<Step> = macro.steps
    private var watchers: List<Step> = macro.watchers
    private var stuck: List<Step> = macro.stuck
    private var counters: List<CounterDef> = macro.counters
    private var stuckSec = macro.stuckSec
    private var schedule = macro.schedule
    private var section = 0          // which list the panel shows: 0 = Main Job, 1 = Watchers, 2 = Stuck rescue

    /** The list being edited right now. */
    private var root: List<Step>
        get() = when (section) { 0 -> mainSteps; 1 -> watchers; else -> stuck }
        set(v) { when (section) { 0 -> mainSteps = v; 1 -> watchers = v; else -> stuck = v } }

    private fun current(): Macro = base.copy(
        steps = mainSteps, watchers = watchers, stuck = stuck, counters = counters, stuckSec = stuckSec, schedule = schedule,
    )

    private fun everyStep(): List<Step> = flat(mainSteps) + flat(watchers) + flat(stuck)

    private fun later(f: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post { f() } }
    private var dirty = false
    private var clipboard: Step? = null

    // read fresh each time, so a turned phone gets menus that fit
    private val screenW: Int get() = Ui.screenBounds(wm).width()
    private val screenH: Int get() = Ui.screenBounds(wm).height()

    private var chip: View? = null
    private var chipX = Ui.dp(ctx, 12)
    private var chipY = Ui.dp(ctx, 170)
    private var panel: View? = null
    private var panelScroll: ScrollView? = null
    private var panelScrollY = 0
    private var menu: View? = null          // the hand's menu, card menus, pickers and dialogs: one at a time
    private var overlay: View? = null       // the tap and swipe pickers
    private var capBox: MarkerView? = null
    private var capPanel: View? = null
    private val flashViews = mutableListOf<View>()
    private var hidden = false

    private fun dp(v: Int) = Ui.dp(ctx, v)

    // ================================================================ the macro tree

    private fun listAt(path: Path): List<Step> {
        var cur = root
        for ((i, ok) in path) {
            val s = cur.getOrNull(i) ?: return emptyList()
            cur = if (ok) s.onSuccess else s.onFail
        }
        return cur
    }

    private fun modifyList(list: List<Step>, path: Path, f: (MutableList<Step>) -> Unit): List<Step> {
        if (path.isEmpty()) return list.toMutableList().also(f)
        val (i, ok) = path.first()
        val s = list[i]
        val child = if (ok) s.onSuccess else s.onFail
        val newChild = modifyList(child, path.drop(1), f)
        return list.toMutableList().also { it[i] = if (ok) s.copy(onSuccess = newChild) else s.copy(onFail = newChild) }
    }

    private fun modify(path: Path, f: (MutableList<Step>) -> Unit) {
        root = modifyList(root, path, f)
        dirty = true
    }

    private fun stepAt(r: Ref): Step? = listAt(r.path).getOrNull(r.index)

    private fun edit(r: Ref, f: (Step) -> Step) = modify(r.path) { if (r.index in it.indices) it[r.index] = f(it[r.index]) }

    private fun flat(list: List<Step>): List<Step> = list.flatMap { listOf(it) + flat(it.onSuccess) + flat(it.onFail) }

    private fun count(list: List<Step>): Int = flat(list).size

    private fun findStep(id: String): Step? = if (id.isEmpty()) null else everyStep().firstOrNull { it.id == id }

    /** Every step with its number, branches included: "2", "2.1", "2.2"... */
    private fun flatLabeled(list: List<Step>, prefix: String = ""): List<Pair<String, Step>> {
        val out = ArrayList<Pair<String, Step>>()
        list.forEachIndexed { i, s ->
            val n = if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}"
            out.add(n to s)
            out += flatLabeled(s.onSuccess, n)
            out += flatLabeled(s.onFail, n)
        }
        return out
    }

    /** How many Run step and Go to steps point at this step. */
    private fun linksTo(id: String): Int = everyStep().count { (it.type == StepType.LINK || it.type == StepType.GOTO) && it.targetId == id }

    private fun linkName(s: Step): String {
        if (s.targetId.isEmpty()) return "pick a step"
        val t = findStep(s.targetId) ?: return "BROKEN LINK (the step was deleted)"
        val n = flatLabeled(mainSteps).firstOrNull { it.second.id == t.id }?.first
        return (if (n != null) "$n  " else "") + stepTitle(t)
    }

    // ================================================================ lifecycle and the hand button

    fun show() {
        addChip()
        val prefs = ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("tour_done", false)) {
            prefs.edit().putBoolean("tour_done", true).apply()
            later { HelpTopics.byId("tour")?.let { showHelp(it, tour = true) } }
        }
    }

    // ================================================================ help: the small (i) buttons

    private var helpView: View? = null

    private fun infoBtn(topic: Topic): View = TextView(ctx).apply {
        text = "ⓘ"
        textSize = 17f
        setTextColor(TokenInts.primary)
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        minimumWidth = dp(40)
        minimumHeight = dp(40)
        contentDescription = "Help: ${topic.title}"
        isClickable = true
        setOnClickListener { showHelp(topic) }
    }

    /** Long-press help for the round buttons. */
    private fun holdHelp(v: View, id: String): View {
        val t = HelpTopics.byId(id) ?: return v
        v.setOnLongClickListener { showHelp(t); true }
        return v
    }

    /** A small window over everything: what it is, and (with MORE) an example, the best tip and a common mistake. It does not close the menu under it. */
    private fun showHelp(topic: Topic, tour: Boolean = false) {
        rm(helpView)
        helpView = null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            background = Ui.rounded(0xFF20232B.toInt(), dp(14).toFloat())
        }
        box.addView(label(topic.title, 19f, TokenInts.text).apply { setTypeface(typeface, Typeface.BOLD) })
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label(topic.short, 15f, TokenInts.text), lp(8))
        val more = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun part(head: String, text: String, color: Int) {
            if (text.isEmpty()) return
            more.addView(label(head, 12f, color).apply { setTypeface(typeface, Typeface.BOLD) }, lp(10))
            more.addView(label(text, 14f, TokenInts.text), lp(2))
        }
        part("EXAMPLE", topic.example, TokenInts.primary)
        part("BEST TIP", topic.tip, TokenInts.success)
        part("COMMON MISTAKE", topic.mistake, TokenInts.warning)
        if (tour) {
            more.addView(label("The round button opens the menu. Manage Actions opens your steps. Tap any step to change it, tap the title to switch between Main Job, Watchers and Stuck rescue.", 14f, TokenInts.text), lp(10))
            more.addView(label("Look for the ⓘ next to steps, conditions and menu rows. Tap it any time you are not sure what something does. Hold the round buttons for a tip too.", 14f, TokenInts.text), lp(8))
        }
        val hasMore = more.childCount > 0
        body.addView(more)
        box.addView(MaxHeightScrollView(ctx, minOf((screenH * 0.55).toInt(), screenH - dp(300))).apply { addView(body) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (tour) row.addView(textButton("ALL TIPS") { rm(helpView); helpView = null; openHelpList() })
        if (hasMore) {
            lateinit var moreBtn: TextView
            moreBtn = textButton("MORE") {
                val show = more.visibility != View.VISIBLE
                more.visibility = if (show) View.VISIBLE else View.GONE
                moreBtn.text = if (show) "LESS" else "MORE"
            }
            row.addView(moreBtn)
        }
        row.addView(textButton("CLOSE") { rm(helpView); helpView = null })
        box.addView(row, lp(8))
        val w = (screenW * 0.9).toInt()
        val p = params(w, WindowManager.LayoutParams.WRAP_CONTENT, (screenW - w) / 2, dp(90), outside = true)
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { rm(helpView); helpView = null; true } else false }
        wm.addView(box, p)
        helpView = box
    }

    /** Every help tip, in groups. */
    private fun openHelpList() {
        val rows = ArrayList<Row>()
        var last = ""
        for (t in HelpTopics.all) {
            if (t.group != last) { rows.add(Row(t.group)); last = t.group }
            rows.add(Row(t.title, icon = Icon.LIST, help = false) { showHelp(t) })
        }
        showMenu("Help topics", rows)
    }

    /** The phone was turned: rebuild what is open so it fits the new size. */
    fun onRotated() {
        closeMenu()
        if (capBox != null) { removeCapture() }
        if (panel != null) refreshPanel()
        else if (chip != null) { rm(chip); chip = null; chipX = chipX.coerceIn(0, (screenW - dp(60)).coerceAtLeast(0)); chipY = chipY.coerceIn(0, (screenH - dp(100)).coerceAtLeast(0)); addChip() }
    }

    fun dismiss() {
        removeFlash()
        removeCapture()
        rm(overlay); overlay = null
        closeMenu()
        rm(panel); panel = null
        rm(chip); chip = null
        rm(helpView); helpView = null
        CaptureService.instance?.fast = false
    }

    private fun rm(v: View?) {
        if (v != null) runCatching { wm.removeView(v) }
    }

    private fun params(w: Int, h: Int, x: Int, y: Int, focusable: Boolean = false, outside: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (outside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /** Makes a window follow a finger. A short tap, without moving, calls [onTap]. */
    private fun makeDraggable(handle: View, window: View, lp: WindowManager.LayoutParams, onMoved: () -> Unit = {}, onTap: () -> Unit = {}) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(window, lp) }
                        onMoved()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
    }

    private fun addChip() {
        rm(chip)
        val size = dp(52)
        val v = FrameLayout(ctx).apply {
            background = Ui.rounded(TokenInts.primary, size / 2f)
            contentDescription = "MacroLoop editor"
        }
        v.addView(IconView(ctx, Icon.TAP, TokenInts.onPrimary), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        val lp = params(size, size, chipX, chipY)
        makeDraggable(v, v, lp, onMoved = { chipX = lp.x; chipY = lp.y }, onTap = { toggleChipMenu() })
        wm.addView(v, lp)
        chip = v
        applyHidden()
    }

    private fun toggleChipMenu() {
        if (menu != null) {
            closeMenu()
            return
        }
        showMenu(
            null,
            listOf(
                Row("Manage Actions", icon = Icon.LIST) { closeMenu(); openPanel() },
                Row("Run Test", icon = Icon.PLAY) { closeMenu(); runTest(0, Ref(emptyList(), 0)) },
                Row("Save", icon = Icon.SAVE) { closeMenu(); save(); toast("Saved") },
                Row("More", icon = Icon.MORE) { openMore() },
                Row("Exit", icon = Icon.CLOSE) { requestExit() },
            ),
            anchor = chipX to (chipY + dp(60)),
        )
    }

    private fun openMore() {
        showMenu(
            "More",
            listOf(
                Row("Random Setting", icon = Icon.GEAR) { randomSettingDialog() },
                Row("Debug Setting", icon = Icon.GEAR) { debugSettingDialog() },
            ),
            anchor = chipX to (chipY + dp(60)),
        )
    }

    private fun randomSettingDialog() {
        val prefs = ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE)
        showDialogBox("Random Setting") { box ->
            box.addView(label("Small random delays and tap offsets, so taps are not exactly the same every time.", 13f, TokenInts.textMuted), lp(4))
            box.addView(checkBox("Human-like timing", prefs.getBoolean(MacroService.PREF_HUMAN, true)) { on ->
                prefs.edit().putBoolean(MacroService.PREF_HUMAN, on).apply()
            }, lp(8))
            box.addView(textButton("CLOSE") { closeMenu() }, lp(8))
        }
    }

    private fun debugSettingDialog() {
        val prefs = ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE)
        showDialogBox("Debug Setting") { box ->
            box.addView(checkBox("Show Detect Location", prefs.getBoolean(MacroService.PREF_SHOW_DETECT, false)) { on ->
                prefs.edit().putBoolean(MacroService.PREF_SHOW_DETECT, on).apply()
            }, lp(8))
            box.addView(checkBox("Highlight Success Detection", prefs.getBoolean(MacroService.PREF_HIGHLIGHT, false)) { on ->
                prefs.edit().putBoolean(MacroService.PREF_HIGHLIGHT, on).apply()
            }, lp(4))
            box.addView(textButton("CLOSE") { closeMenu() }, lp(8))
        }
    }

    private fun requestExit() {
        closeMenu()
        showDialogBox("Confirm") { box ->
            box.addView(label("Just to make sure you didn't forget anything", 14f, TokenInts.textMuted), lp(6))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(textButton("SAVE AND EXIT") { save(); onClosed() })
            row.addView(textButton("CANCEL") { closeMenu() })
            row.addView(textButton("EXIT") { onClosed() })
            box.addView(row, lp(12))
        }
    }

    // ================================================================ the list panel

    private fun openPanel() {
        closeMenu()
        rm(chip); chip = null
        refreshPanel()
    }

    private fun shrinkToChip() {
        closeMenu()
        rm(panel); panel = null; panelScroll = null
        addChip()
    }

    private fun refreshPanel() {
        panelScroll?.let { panelScrollY = it.scrollY }
        rm(panel)
        panel = null
        panelScroll = null

        val rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = Ui.rounded(0xFF15181F.toInt(), dp(16).toFloat())
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(holdHelp(IconBtn(ctx, Icon.SHRINK) { shrinkToChip() }, "btn_shrink"), LinearLayout.LayoutParams(dp(44), dp(44)))
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label(macroName, 17f, TokenInts.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val n = count(root)
        val secName = listOf("Main Job", "Watchers", "Stuck rescue")[section]
        titleCol.addView(label("$secName ▾   " + (if (n == 1) "1 step" else "$n steps") + (if (dirty) "  (not saved)" else ""), 11f, TokenInts.primary))
        titleCol.isClickable = true
        titleCol.setOnClickListener { openMacroMenu() }
        head.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(6) })
        head.addView(holdHelp(IconBtn(ctx, Icon.PLAY, TokenInts.success) {
            showMenu("Test", listOf(Row("Run Test: From beginning", icon = Icon.PLAY, help = false) { closeMenu(); runTest(0, Ref(emptyList(), 0)) }), anchor = (screenW - dp(250)) to dp(90))
        }, "btn_test"), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        head.addView(holdHelp(IconBtn(ctx, Icon.PLUS) { if (section == 1) addWatcher() else openActionPicker(Target(emptyList())) }, "btn_plus"), LinearLayout.LayoutParams(dp(44), dp(44)))
        rootView.addView(head)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        when (section) {
            1 -> body.addView(label("Watchers look at the screen about once a second while the macro runs. When all of a watcher's conditions hold, its If true steps run (for example, close a popup) and the macro carries on where it was.", 12f, TokenInts.textMuted), lp(4))
            2 -> body.addView(label(
                (if (stuckSec > 0) "Stuck check is ON: if the screen does not change for $stuckSec s, these steps run. " else "Stuck check is OFF (turn it on from the title menu). ") +
                    "With no steps here, the macro stops with a message instead.", 12f, TokenInts.textMuted), lp(4))
        }
        if (root.isEmpty()) {
            body.addView(label(when (section) {
                1 -> "No watchers yet. Tap + to add one."
                2 -> "No rescue steps. Tap + to add what should happen when the macro is stuck."
                else -> "No steps yet. Tap + to add the first one."
            }, 14f, TokenInts.textMuted), lp(16))
        } else {
            renderSteps(body, root, emptyList(), "")
        }
        val scroll = MaxHeightScrollView(ctx, minOf((screenH * 0.8).toInt(), screenH - dp(130))).apply {
            addView(body)
            isVerticalScrollBarEnabled = true
        }
        panelScroll = scroll
        rootView.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val p = params(screenW - dp(16), WindowManager.LayoutParams.WRAP_CONTENT, dp(8), dp(28))
        wm.addView(rootView, p)
        panel = rootView
        scroll.post { scroll.scrollTo(0, panelScrollY) }
        applyHidden()
    }

    private fun renderSteps(box: LinearLayout, list: List<Step>, path: Path, prefix: String) {
        val cards = ArrayList<View>()
        list.forEachIndexed { i, s ->
            val num = if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}"
            val card = buildCard(s, Ref(path, i), num, cards)
            cards.add(card)
            box.addView(card, lp(6))
            if (s.onSuccess.isNotEmpty()) addBranch(box, s.onSuccess, path + (i to true), num, true)
            if (s.onFail.isNotEmpty()) addBranch(box, s.onFail, path + (i to false), num, false)
        }
    }

    private fun addBranch(box: LinearLayout, list: List<Step>, path: Path, num: String, ifTrue: Boolean) {
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(6), dp(4))
            background = Ui.rounded(if (ifTrue) 0x264ADE80 else 0x26FBBF24, dp(12).toFloat())
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openActionPicker(Target(path)) }
        }
        head.addView(label(if (ifTrue) "If true" else "If false", 15f, if (ifTrue) TokenInts.success else TokenInts.warning).apply { setTypeface(typeface, Typeface.BOLD) })
        head.addView(
            label(if (ifTrue) "   step $num worked, then:" else "   step $num found nothing, then:", 11f, TokenInts.textMuted),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(label("+", 18f, TokenInts.primary).apply { setPadding(dp(10), 0, dp(6), 0) })
        group.addView(head)
        renderSteps(group, list, path, num)
        box.addView(group, lp(4).apply { marginStart = dp(12) })
    }

    // ---------------------------------------------------------------- cards

    private fun sign(v: Int) = if (v >= 0) "+$v" else "$v"

    private fun clickStyleCode(s: Step): String = "X${s.clicks}" + (if (s.holdMs > 0 || s.holdUntil != null) "H" else "") + (if (s.twoFingers) " 2F" else "")

    private fun targetName(s: Step): String {
        val k = mainSteps.indexOfFirst { it.id == s.targetId }
        return when {
            s.targetId.isEmpty() -> "pick a step"
            k < 0 -> "the step was deleted"
            else -> "step ${k + 1}  ${stepTitle(mainSteps[k])}"
        }
    }

    private fun appLabel(pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.ifBlank { "pick an app" })

    /** The user's own name for the step if it has one, otherwise a plain description. */
    private fun stepTitle(s: Step): String = if (s.name.isNotBlank()) s.name else when (s.type) {
        StepType.CLICK_IMAGE -> "Click image"
        StepType.LINK -> "Run: " + linkName(s)
        else -> s.describe()
    }

    private fun titleRow(s: Step): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (s.name.isNotBlank()) {
            row.addView(label(s.name, 15f, TokenInts.text).apply { setTypeface(typeface, Typeface.BOLD) })
            return row
        }
        when (s.type) {
            StepType.CLICK_IMAGE -> {
                row.addView(label("Click", 15f, TokenInts.text))
                thumb(s.templateId)?.let { row.addView(it, LinearLayout.LayoutParams(dp(68), dp(30)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
                row.addView(label("[${sign(s.offsetX)}/${sign(s.offsetY)}]", 12f, TokenInts.textMuted))
            }
            StepType.CLICK -> row.addView(label("Click (${s.x}, ${s.y})  [${sign(s.offsetX)}/${sign(s.offsetY)}]", 15f, TokenInts.text))
            StepType.LINK -> row.addView(label("Run: " + linkName(s), 15f, if (findStep(s.targetId) == null) TokenInts.danger else TokenInts.text))
            else -> row.addView(label(s.describe(), 15f, TokenInts.text))
        }
        return row
    }

    private fun subline(s: Step): String {
        val parts = ArrayList<String>()
        if (s.name.isNotBlank()) {
            parts.add(
                when (s.type) {
                    StepType.CLICK_IMAGE -> "Click image"
                    else -> s.describe()
                },
            )
        }
        when (s.type) {
            StepType.CLICK, StepType.CLICK_IMAGE -> {
                parts.add("[${clickStyleCode(s)}]")
                if (s.holdUntil != null) parts.add("[Hold until picture, max ${s.holdMaxMs / 1000}s]")
                else if (s.holdMs > 0) parts.add("[Hold ${s.holdMs}ms]")
                if (s.clicks > 1) parts.add("[Gap ${s.clickGapMs}ms]")
                if (s.randomizePx > 0) parts.add("[Random ${s.randomizePx}px]")
                parts.add("[Delay ${s.delayMs}ms/${s.waitNextMs}ms]")
            }
            StepType.SWIPE -> {
                parts.add("[${s.swipeMs}ms]")
                parts.add("[Delay ${s.delayMs}ms/${s.waitNextMs}ms]")
            }
            StepType.GOTO -> parts.add("→ ${targetName(s)}")
            StepType.LINK -> parts.add("[link: runs it, then carries on]")
            StepType.TOAST -> parts.add("\"${s.text.ifBlank { "..." }}\"")
            StepType.OPEN_APP -> parts.add(appLabel(s.text))
            StepType.WAIT, StepType.STOP_MACRO -> Unit
            StepType.COUNTER_ADD, StepType.COUNTER_RESET -> parts.add("[counter ${s.text.ifBlank { "not chosen" }}]")
            StepType.WATCH -> parts.add(if (s.conditions.isEmpty()) "[add a condition]" else "[looks about once a second]")
            else -> parts.add("[Delay ${s.delayMs}ms/${s.waitNextMs}ms]")
        }
        if (s.linkedOnly) parts.add("[Runs only when linked]")
        val n = linksTo(s.id)
        if (n > 0 && s.type != StepType.LINK && s.type != StepType.GOTO) parts.add("[linked ×$n]")
        if (!s.enabled) parts.add("OFF")
        return parts.joinToString("  ")
    }

    private fun glyph(t: StepType) = when (t) {
        StepType.CLICK -> "●"
        StepType.CLICK_IMAGE -> "▣"
        StepType.SWIPE -> "➜"
        StepType.WAIT -> "⏱"
        StepType.STOP_MACRO -> "■"
        StepType.GOTO -> "→"
        StepType.LINK -> "↪"
        StepType.TOAST -> "✎"
        StepType.OPEN_APP -> "▶"
        StepType.COUNTER_ADD -> "+"
        StepType.COUNTER_RESET -> "0"
        StepType.WATCH -> "◉"
        else -> "◆"
    }

    private fun circle(text: String, fill: Int, textColor: Int, sizeDp: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sizeDp * 0.42f
        setTextColor(textColor)
        gravity = Gravity.CENTER
        background = Ui.rounded(fill, dp(sizeDp) / 2f)
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(TokenInts.onPrimary)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = Ui.rounded(TokenInts.success, dp(14).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** One row of the logic tree under a card: a green mark, what has to be true, and how sure it must be. */
    private fun treeRow(icon: String, ok: Boolean, content: View, second: String?, help: Topic? = null, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(circle(icon, if (ok) TokenInts.success else TokenInts.danger, TokenInts.onPrimary, 28), LinearLayout.LayoutParams(dp(28), dp(28)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content)
        if (second != null) col.addView(label(second, 11f, TokenInts.textMuted))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        if (help != null) row.addView(infoBtn(help).apply { setPadding(dp(4), 0, dp(4), 0) })
        return row
    }

    private fun imageContent(templateId: String, appear: Boolean, pct: Int): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(label("Image", 13f, TokenInts.text))
        thumb(templateId)?.let { line.addView(it, LinearLayout.LayoutParams(dp(68), dp(30)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        line.addView(label("[${if (appear) "Appears" else "Not appear"}] [$pct%]", 12f, TokenInts.textMuted))
        return line
    }

    private fun conditionContent(c: Condition): Pair<View, String?> = when (c.kind) {
        ConditionKind.IMAGE -> imageContent(c.templateId, c.mustAppear, c.threshold) to (if (c.wholeScreen) "[Whole Screen]" else "[Captured Location]")
        ConditionKind.ACTION_RESULT -> label("Action Result  [${if (c.resultOk) "Success" else "Failed"}]", 13f, TokenInts.text) to null
        ConditionKind.RANDOM -> label("Random True  [${c.percent}%]", 13f, TokenInts.text) to null
        ConditionKind.ONCE -> label("Once True", 13f, TokenInts.text) to null
        ConditionKind.COLOR -> colorContent(c) to (if (c.changes) "[Area colour]" else if (c.wholeScreen) "[Whole Screen]" else "[Captured Location]")
        ConditionKind.TEXT -> label("Text \"${c.text}\"  [${if (c.mustAppear) "Appears" else "Not appear"}]", 13f, TokenInts.text) to
            (if (c.wholeScreen) "[Whole Screen]" else "[Captured Location]")
        ConditionKind.COUNTER -> label("Counter ${c.text}  [${if (c.mustAppear) "at least" else "below"} ${c.percent}]", 13f, TokenInts.text) to null
    }.let { (v, second) ->
        if (c.enabled) v to second else { v.alpha = 0.4f; v to "[Off]" + (second?.let { "  $it" } ?: "") }
    }

    private fun colorContent(c: Condition): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(label("Colour", 13f, TokenInts.text))
        if (!c.changes) {
            line.addView(View(ctx).apply { background = Ui.rounded(c.color or (0xFF shl 24), dp(4).toFloat()) },
                LinearLayout.LayoutParams(dp(30), dp(22)).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        val what = if (c.changes) "Changes" else if (c.mustAppear) "Appears" else "Not appear"
        line.addView(label("[$what] [${c.tolerance}%]", 12f, TokenInts.textMuted).apply { if (c.changes) setPadding(dp(6), 0, 0, 0) })
        return line
    }

    private fun buildCard(s: Step, ref: Ref, num: String, cards: List<View>): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = Ui.rounded(if (s.enabled) 0xFF242833.toInt() else 0xFF1A1D24.toInt(), dp(12).toFloat())
            alpha = if (s.enabled) 1f else 0.55f
            isClickable = true
            setOnClickListener { openCardMenu(ref) }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(circle(glyph(s.type), TokenInts.primary, TokenInts.onPrimary, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleRow(s))
        val sub = subline(s)
        if (sub.isNotEmpty()) col.addView(label(sub, 11f, TokenInts.textMuted))
        top.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        HelpTopics.forStep(s.type)?.let { top.addView(infoBtn(it).apply { setPadding(dp(4), 0, dp(4), 0) }) }
        // The number tab is also the handle: hold it and slide the card up or down to change its order.
        val tab = label(num, 12f, TokenInts.onPrimary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.rounded(TokenInts.primary, dp(8).toFloat())
            contentDescription = "Step $num. Hold and slide to reorder"
        }
        top.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        attachDrag(tab, card, cards, ref)
        card.addView(top)

        // the logic tree: this step's own picture, then the extra conditions, joined by All true / One true
        val tree = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), 0, 0)
        }
        var rows = 0
        if (s.type == StepType.CLICK_IMAGE) {
            tree.addView(
                treeRow("✓", true, imageContent(s.templateId, true, s.threshold), if (s.wholeScreen) "[Whole Screen]" else "[Captured Location]", help = HelpTopics.byId("own_picture")) {
                    openOwnPictureMenu(ref)
                },
            )
            rows++
        }
        s.conditions.forEachIndexed { j, c ->
            if (rows > 0) {
                val chipRow = LinearLayout(ctx).apply { setPadding(dp(34), dp(1), 0, dp(1)) }
                chipRow.addView(
                    pill(if (s.anyOf) "One true" else "All true") {
                        edit(ref) { it.copy(anyOf = !it.anyOf) }
                        refreshPanel()
                    },
                )
                HelpTopics.byId("all_true")?.let { chipRow.addView(infoBtn(it).apply { setPadding(dp(8), 0, dp(4), 0) }) }
                tree.addView(chipRow)
            }
            val (content, second) = conditionContent(c)
            val ok = c.changes || c.mustAppear || (c.kind != ConditionKind.IMAGE && c.kind != ConditionKind.COLOR && c.kind != ConditionKind.TEXT)
            tree.addView(treeRow(if (!c.enabled) "–" else if (ok) "✓" else "✕", ok && c.enabled, content, second, help = HelpTopics.forCondition(c)) { openConditionMenu(ref, j) })
            rows++
        }
        s.holdUntil?.let { h ->
            val (content, second) = conditionContent(h)
            val hold = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            hold.addView(label("Hold until:", 12f, TokenInts.warning))
            hold.addView(content)
            tree.addView(treeRow("⏳", true, hold, (second ?: "") + "  max ${s.holdMaxMs / 1000}s", help = HelpTopics.byId("hold_row")) { openHoldMenu(ref) })
            rows++
        }
        if (rows > 0) card.addView(tree)
        return card
    }

    private fun attachDrag(handle: View, card: View, cards: List<View>, ref: Ref) {
        var startRaw = 0f
        handle.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startRaw = e.rawY
                    card.translationZ = dp(8).toFloat()
                    card.alpha = 0.85f
                }
                MotionEvent.ACTION_MOVE -> card.translationY = e.rawY - startRaw
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val loc = IntArray(2)
                    card.getLocationOnScreen(loc)
                    val center = loc[1] + card.height / 2
                    var newIndex = 0
                    for (c in cards) {
                        if (c === card) continue
                        val l = IntArray(2)
                        c.getLocationOnScreen(l)
                        if (l[1] + c.height / 2 < center) newIndex++
                    }
                    card.translationY = 0f
                    card.translationZ = 0f
                    card.alpha = 1f
                    if (e.action == MotionEvent.ACTION_UP && newIndex != ref.index) {
                        modify(ref.path) {
                            if (ref.index in it.indices) {
                                val moved = it.removeAt(ref.index)
                                it.add(newIndex.coerceIn(0, it.size), moved)
                            }
                        }
                        refreshPanel()
                    }
                }
            }
            true
        }
    }

    // ================================================================ menus and dialogs

    private fun closeMenu() {
        rm(menu)
        menu = null
    }

    /** A popup list, like Macrorify's: an icon and a word per row. Tap outside to close. */
    private fun showMenu(title: String?, rows: List<Row>, anchor: Pair<Int, Int>? = null) {
        closeMenu()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(10), dp(6), dp(6))
            background = Ui.rounded(0xFF2B2E36.toInt(), dp(14).toFloat())
        }
        if (title != null) {
            box.addView(label(title, 14f, TokenInts.textMuted).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), dp(6))
            })
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows) {
            val act = r.action
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(if (act == null) 30 else 46)
                setPadding(dp(12), dp(if (act == null) 8 else 2), dp(12), dp(2))
            }
            if (act == null) {
                line.addView(label(r.text, 12f, TokenInts.textMuted).apply { setTypeface(typeface, Typeface.BOLD) })
            } else {
                if (r.icon != null) line.addView(IconView(ctx, r.icon, if (r.color == TokenInts.text) TokenInts.primary else r.color), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                line.addView(label(r.text, 16f, r.color), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (r.help) HelpTopics.find(r.text)?.let { line.addView(infoBtn(it)) }
                line.isClickable = true
                line.setOnClickListener { act() }
            }
            list.addView(line)
        }
        if (anchor == null) {
            list.addView(
                label("Cancel", 16f, TokenInts.textMuted).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(46)
                    setPadding(dp(12), 0, dp(12), 0)
                    isClickable = true
                    setOnClickListener { closeMenu() }
                },
            )
        }
        box.addView(MaxHeightScrollView(ctx, minOf((screenH * 0.78).toInt(), screenH - dp(230))).apply { addView(list) })
        val w = if (anchor != null) dp(260) else (screenW * 0.9).toInt()
        val p = if (anchor != null) {
            params(w, WindowManager.LayoutParams.WRAP_CONTENT, anchor.first.coerceIn(0, screenW - w), anchor.second.coerceIn(0, screenH - dp(300)), outside = true)
        } else {
            params(w, WindowManager.LayoutParams.WRAP_CONTENT, (screenW - w) / 2, dp(80), outside = true)
        }
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                closeMenu()
                true
            } else false
        }
        wm.addView(box, p)
        menu = box
        applyHidden()
    }

    /** A small dialog with the keyboard available. [build] fills it. */
    private fun showDialogBox(title: String, focusable: Boolean = false, build: (LinearLayout) -> Unit) {
        closeMenu()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = Ui.rounded(0xFF2B2E36.toInt(), dp(14).toFloat())
        }
        box.addView(label(title, 20f, TokenInts.text))
        build(box)
        val w = (screenW * 0.9).toInt()
        val p = params(w, WindowManager.LayoutParams.WRAP_CONTENT, (screenW - w) / 2, dp(110), focusable = focusable)
        if (focusable) p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        wm.addView(box, p)
        menu = box
        applyHidden()
    }

    /** A dialog with text boxes and CANCEL / SAVE, like Macrorify's Offset and Delay dialogs. */
    private fun showForm(title: String, note: String?, build: (LinearLayout) -> Unit, onSave: () -> Unit) {
        showDialogBox(title, focusable = true) { box ->
            build(box)
            if (note != null) box.addView(label(note, 13f, TokenInts.textMuted), lp(10))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(textButton("CANCEL") { closeMenu() })
            row.addView(textButton("SAVE") {
                onSave()
                closeMenu()
                refreshPanelIfOpen()
            })
            box.addView(row, lp(10))
        }
    }

    private fun refreshPanelIfOpen() {
        if (panel != null) refreshPanel()
    }

    private fun field(box: LinearLayout, hint: String, initial: String, signed: Boolean = false, decimal: Boolean = false, text: Boolean = false): EditText {
        val labelView = label(hint, 12f, TokenInts.textMuted)
        box.addView(labelView, lp(10))
        val e = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            textSize = 17f
            setTextColor(TokenInts.text)
            setSingleLine(true)
            inputType = when {
                text -> InputType.TYPE_CLASS_TEXT
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                else -> InputType.TYPE_CLASS_NUMBER
            }
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), TokenInts.textMuted)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(e, lp(2))
        return e
    }

    private fun showChoice(title: String, options: List<String>, selected: Int, onSave: (Int) -> Unit) {
        var pick = selected
        showDialogBox(title) { box ->
            val marks = ArrayList<TextView>()
            options.forEachIndexed { k, name ->
                val row = label((if (k == pick) "◉  " else "○  ") + name, 16f, TokenInts.text).apply {
                    minimumHeight = dp(46)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                }
                row.setOnClickListener {
                    pick = k
                    marks.forEachIndexed { m, t -> t.text = (if (m == pick) "◉  " else "○  ") + options[m] }
                }
                marks.add(row)
                box.addView(row, lp(2))
            }
            val buttons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.addView(textButton("CANCEL") { closeMenu() })
            buttons.addView(textButton("SAVE") {
                onSave(pick)
                closeMenu()
                refreshPanelIfOpen()
            })
            box.addView(buttons, lp(10))
        }
    }

    private fun textButton(text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(TokenInts.primary)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun checkBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(TokenInts.text)
        isChecked = checked
        minHeight = dp(48)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    // ================================================================ the plus menu: every action

    private fun pickerButton(icon: Icon, text: String, onClick: () -> Unit): View {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = Ui.rounded(0xFF3A3F4C.toInt(), dp(14).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        }
        b.addView(
            FrameLayout(ctx).apply {
                background = Ui.rounded(TokenInts.primary, dp(18).toFloat())
                addView(IconView(ctx, icon, TokenInts.onPrimary), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        b.addView(label(text, 14f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        HelpTopics.find(text)?.let { b.addView(infoBtn(it).apply { minimumWidth = dp(28); setPadding(dp(4), 0, dp(2), 0) }) }
        return b
    }

    private fun gridRows(box: LinearLayout, items: List<Triple<Icon, String, () -> Unit>>) {
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { k, (icon, text, act) ->
                row.addView(pickerButton(icon, text, act), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (k == 0) marginEnd = dp(6) })
            }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            box.addView(row, lp(6))
        }
    }

    private fun sectionTitle(box: LinearLayout, text: String) {
        box.addView(label(text, 14f, TokenInts.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), 0, 0)
        }, lp(8))
    }

    private fun openActionPicker(target: Target) {
        closeMenu()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            background = Ui.rounded(0xFF2B2E36.toInt(), dp(16).toFloat())
        }
        box.addView(label(if (target.replace != null) "Replace Action" else "Action", 20f, TokenInts.text))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun pick(kind: StepType) = { closeMenu(); createStep(kind, target) }
        if (clipboard != null && target.replace == null) {
            sectionTitle(body, "Clipboard")
            gridRows(body, listOf(Triple(Icon.COPY, "Paste") {
                closeMenu()
                clipboard?.let { c -> finishAdd(target, renumber(c)) }
            }))
        }
        sectionTitle(body, "Gesture")
        gridRows(
            body,
            listOf(
                Triple(Icon.TAP, "Click (x, y)", pick(StepType.CLICK)),
                Triple(Icon.PICTURE, "Click Image", pick(StepType.CLICK_IMAGE)),
                Triple(Icon.SWIPE, "Swipe (x, y)", pick(StepType.SWIPE)),
            ),
        )
        sectionTitle(body, "Device")
        gridRows(
            body,
            listOf(
                Triple(Icon.MORE, "Toast Message", pick(StepType.TOAST)),
                Triple(Icon.PLAY, "Open App", pick(StepType.OPEN_APP)),
                Triple(Icon.BACK, "Press Back", pick(StepType.PRESS_BACK)),
                Triple(Icon.TAP, "Press Home", pick(StepType.PRESS_HOME)),
                Triple(Icon.LIST, "Open Recent", pick(StepType.OPEN_RECENT)),
                Triple(Icon.MORE, "Open Notification", pick(StepType.OPEN_NOTIFICATION)),
                Triple(Icon.GEAR, "Open Quicksetting", pick(StepType.OPEN_QUICKSETTING)),
                Triple(Icon.CLOSE, "Lock Screen", pick(StepType.LOCK_SCREEN)),
            ),
        )
        sectionTitle(body, "Counter")
        gridRows(
            body,
            listOf(
                Triple(Icon.PLUS, "Add to Counter", pick(StepType.COUNTER_ADD)),
                Triple(Icon.REDO, "Reset Counter", pick(StepType.COUNTER_RESET)),
            ),
        )
        sectionTitle(body, "Macro")
        gridRows(
            body,
            listOf(
                Triple(Icon.PLAY, "Wait", pick(StepType.WAIT)),
                Triple(Icon.CLOSE, "Stop Macro", pick(StepType.STOP_MACRO)),
                Triple(Icon.REDO, "Go to Step", pick(StepType.GOTO)),
                Triple(Icon.LINK, "Run Step (link)", pick(StepType.LINK)),
            ),
        )
        box.addView(MaxHeightScrollView(ctx, minOf((screenH * 0.72).toInt(), screenH - dp(210))).apply { addView(body) })
        box.addView(textButton("CANCEL") { closeMenu() }.apply { gravity = Gravity.END }, lp(6))
        val w = (screenW * 0.94).toInt()
        val p = params(w, WindowManager.LayoutParams.WRAP_CONTENT, (screenW - w) / 2, dp(70), outside = true)
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false }
        wm.addView(box, p)
        menu = box
        applyHidden()
    }

    private fun renumber(s: Step): Step = s.copy(
        id = java.util.UUID.randomUUID().toString(),
        onSuccess = s.onSuccess.map { renumber(it) },
        onFail = s.onFail.map { renumber(it) },
    )

    /** Puts a new step where [target] says. A replaced step keeps its name, conditions, branches and on/off. */
    private fun finishAdd(target: Target, step: Step) {
        modify(target.path) { list ->
            val at = target.replace
            if (at != null && at in list.indices) {
                val old = list[at]
                list[at] = step.copy(
                    id = old.id, name = old.name, conditions = old.conditions, anyOf = old.anyOf,
                    onSuccess = old.onSuccess, onFail = old.onFail, enabled = old.enabled,
                )
            } else {
                list.add((target.insertAt ?: list.size).coerceIn(0, list.size), step)
            }
        }
        refreshPanel()
    }

    private fun createStep(kind: StepType, target: Target) {
        when (kind) {
            StepType.CLICK -> pickSpot("Tap where the macro should tap") { x, y -> finishAdd(target, Step(StepType.CLICK, x = x, y = y)) }
            StepType.SWIPE -> pickSwipe { x1, y1, x2, y2 -> finishAdd(target, Step(StepType.SWIPE, x = x1, y = y1, x2 = x2, y2 = y2)) }
            StepType.CLICK_IMAGE -> newOrExist { box, id, fw, fh ->
                val n = flat(root).count { it.type == StepType.CLICK_IMAGE } + 1
                finishAdd(target, Step(
                    StepType.CLICK_IMAGE, templateId = id, label = "Image $n", wholeScreen = true,
                    boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh,
                ))
            }
            StepType.WAIT -> finishAdd(target, Step(StepType.WAIT, ms = 1000))
            StepType.TOAST -> showForm("Toast Message", null, { b ->
                toastField = field(b, "Message", "", text = true)
            }, { finishAdd(target, Step(StepType.TOAST, text = toastField?.text?.toString().orEmpty())) })
            StepType.OPEN_APP -> openAppPicker { pkg -> finishAdd(target, Step(StepType.OPEN_APP, text = pkg)) }
            StepType.GOTO -> openLinkPicker { t -> finishAdd(target, Step(StepType.GOTO, targetId = t.id)) }
            StepType.LINK -> openStepPicker("Run Step", null) { t -> finishAdd(target, Step(StepType.LINK, targetId = t.id)); askLinkedOnly(t) }
            StepType.COUNTER_ADD -> pickCounter("Add to which counter?") { name ->
                var f: EditText? = null
                showForm("Add to $name", "How much to add each time. A minus number takes away.", { b -> f = field(b, "Amount", "1", signed = true) },
                    { finishAdd(target, Step(StepType.COUNTER_ADD, text = name, amount = f?.text.toString().toIntOrNull() ?: 1)) })
            }
            StepType.COUNTER_RESET -> pickCounter("Reset which counter?") { name -> finishAdd(target, Step(StepType.COUNTER_RESET, text = name)) }
            else -> finishAdd(target, Step(kind))
        }
    }

    private var toastField: EditText? = null

    private fun openLinkPicker(onPick: (Step) -> Unit) {
        val rows = ArrayList<Row>()
        rows.add(Row("Pick the step the macro should jump to (a step of the Main Job)"))
        mainSteps.forEachIndexed { k, st ->
            rows.add(Row("${k + 1}.  ${stepTitle(st)}", icon = Icon.REDO) { closeMenu(); onPick(st) })
        }
        if (mainSteps.isEmpty()) rows.add(Row("There are no steps to jump to yet."))
        showMenu("Go to Step", rows)
    }

    /** Lists every step of the macro, branches included, so one can be linked. */
    private fun openStepPicker(title: String, exclude: String?, onPick: (Step) -> Unit) {
        val rows = ArrayList<Row>()
        rows.add(Row("Pick the step to run. It runs, then the list carries on."))
        for ((n, st) in flatLabeled(mainSteps)) {
            if (st.id == exclude || st.type == StepType.LINK && st.targetId == exclude) continue
            val nested = n.contains('.')
            rows.add(Row((if (nested) "    " else "") + "$n.  ${stepTitle(st)}" + (if (st.linkedOnly) "   (only when linked)" else ""), icon = Icon.LINK) { closeMenu(); onPick(st) })
        }
        if (mainSteps.isEmpty()) rows.add(Row("There are no steps to link yet."))
        showMenu(title, rows)
    }

    /**
     * A step in the main list that is also linked would run twice: once in its place and once from the link.
     * Ask, in plain words, whether it should run only when linked.
     */
    private fun askLinkedOnly(t: Step) {
        val inMain = mainSteps.any { it.id == t.id }
        if (!inMain || t.linkedOnly) return
        showDialogBox("Run it only when linked?") { box ->
            box.addView(
                label(
                    "\"${stepTitle(t)}\" is also in the main list, so it would run in its place and again from the link. " +
                        "Make it run only when a link calls it? You can undo this in its menu.",
                    14f, TokenInts.textMuted,
                ),
                lp(8),
            )
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            row.addView(textButton("ONLY WHEN LINKED (recommended)") {
                modifyById(t.id) { it.copy(linkedOnly = true) }
                closeMenu()
                refreshPanel()
            })
            row.addView(textButton("KEEP IT IN THE ORDER TOO") { closeMenu(); refreshPanel() })
            box.addView(row, lp(8))
        }
    }

    /** Changes a step wherever it is in the tree. */
    private fun modifyById(id: String, f: (Step) -> Step) {
        fun walk(list: List<Step>): List<Step> = list.map { s ->
            val n = if (s.id == id) f(s) else s
            n.copy(onSuccess = walk(n.onSuccess), onFail = walk(n.onFail))
        }
        root = walk(root)
        dirty = true
    }

    private fun openAppPicker(onPick: (String) -> Unit) {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val rows = ArrayList<Row>()
        rows.add(Row("Pick the app to open"))
        for ((pkg, name) in apps) rows.add(Row(name, icon = Icon.PLAY) { closeMenu(); onPick(pkg) })
        showMenu("Open App", rows)
    }

    // ================================================================ the card menu (Macrorify's rows, plus ours)

    private fun openCardMenu(ref: Ref) {
        val s = stepAt(ref) ?: return
        val rows = ArrayList<Row>()
        if (s.type == StepType.WATCH) {
            openWatchMenu(ref, s)
            return
        }
        val logical = s.type != StepType.WAIT && s.type != StepType.STOP_MACRO && s.type != StepType.GOTO && s.type != StepType.LINK &&
            s.type != StepType.COUNTER_ADD && s.type != StepType.COUNTER_RESET
        fun add(text: String, icon: Icon?, color: Int = TokenInts.text, act: () -> Unit) = rows.add(Row(text, color, icon, action = act))

        when (s.type) {
            StepType.CLICK_IMAGE -> {
                add("Re-crop Image", Icon.PENCIL) { closeMenu(); recropStep(ref) }
                add("Change Image", Icon.PENCIL) { closeMenu(); changeImage(ref) }
                add("Edit Offset", Icon.PENCIL) { editOffsetDialog(ref) }
                add("Edit Click Style", Icon.PENCIL) { editClickStyleDialog(ref) }
                add(if (s.holdUntil == null) "Hold Until..." else "Edit Hold Until", Icon.PENCIL) { holdUntilEntry(ref) }
                add("Edit Delay", Icon.PENCIL) { editDelayDialog(ref) }
                add("Edit Image", Icon.PENCIL) { editImageDialog(ref) }
            }
            StepType.CLICK -> {
                add("Move Spot", Icon.PENCIL) { closeMenu(); moveSpot(ref) }
                add("Edit Offset", Icon.PENCIL) { editOffsetDialog(ref) }
                add("Edit Click Style", Icon.PENCIL) { editClickStyleDialog(ref) }
                add(if (s.holdUntil == null) "Hold Until..." else "Edit Hold Until", Icon.PENCIL) { holdUntilEntry(ref) }
                add("Edit Delay", Icon.PENCIL) { editDelayDialog(ref) }
            }
            StepType.SWIPE -> {
                add("Redraw Swipe", Icon.PENCIL) { closeMenu(); redrawSwipe(ref) }
                add("Edit Swipe Speed", Icon.PENCIL) { editSwipeDialog(ref) }
                add("Edit Delay", Icon.PENCIL) { editDelayDialog(ref) }
            }
            StepType.WAIT -> add("Edit Wait Time", Icon.PENCIL) { editWaitDialog(ref) }
            StepType.GOTO -> add("Change Where It Goes", Icon.PENCIL) {
                closeMenu()
                openLinkPicker { t -> edit(ref) { it.copy(targetId = t.id) }; refreshPanel() }
            }
            StepType.TOAST -> add("Edit Message", Icon.PENCIL) { editToastDialog(ref) }
            StepType.OPEN_APP -> add("Change App", Icon.PENCIL) {
                closeMenu()
                openAppPicker { pkg -> edit(ref) { it.copy(text = pkg) }; refreshPanel() }
            }
            StepType.LINK -> add("Change Link", Icon.LINK) {
                closeMenu()
                openStepPicker("Run Step", s.id) { t -> edit(ref) { it.copy(targetId = t.id) }; askLinkedOnly(t); refreshPanel() }
            }
            StepType.STOP_MACRO -> Unit
            StepType.COUNTER_ADD -> {
                add("Change Counter", Icon.PENCIL) { closeMenu(); pickCounter("Add to which counter?") { n -> edit(ref) { it.copy(text = n) }; refreshPanel() } }
                add("Edit Amount", Icon.PENCIL) {
                    var f: EditText? = null
                    showForm("Amount", "How much to add each time. A minus number takes away.", { b -> f = field(b, "Amount", s.amount.toString(), signed = true) },
                        { edit(ref) { it.copy(amount = f?.text.toString().toIntOrNull() ?: 1) } })
                }
            }
            StepType.COUNTER_RESET -> add("Change Counter", Icon.PENCIL) {
                closeMenu(); pickCounter("Reset which counter?") { n -> edit(ref) { it.copy(text = n) }; refreshPanel() }
            }
            else -> add("Edit Delay", Icon.PENCIL) { editDelayDialog(ref) }
        }
        add("Test this step", Icon.PLAY, TokenInts.success) { runTest(1, ref) }
        add("Test from this step", Icon.PLAY, TokenInts.success) { runTest(2, ref) }
        add("Add Action (Above)", Icon.PLUS) { openActionPicker(Target(ref.path, insertAt = ref.index)) }
        if (logical) add("Add Condition", Icon.PLUS) { openConditionPicker(ref) }
        add("Replace Action", Icon.REDO) { openActionPicker(Target(ref.path, replace = ref.index)) }
        add("Rename", Icon.PENCIL) { renameDialog(ref) }
        if (logical) {
            add("Add step if true", Icon.PLUS, TokenInts.success) { openActionPicker(Target(ref.path + (ref.index to true))) }
            add("Add step if false", Icon.PLUS, TokenInts.warning) { openActionPicker(Target(ref.path + (ref.index to false))) }
        }
        if (logical) {
            add("Link a step if true", Icon.LINK, TokenInts.success) {
                closeMenu()
                openStepPicker("Run Step if true", s.id) { t -> addLinkTo(Target(ref.path + (ref.index to true)), t) }
            }
            add("Link a step if false", Icon.LINK, TokenInts.warning) {
                closeMenu()
                openStepPicker("Run Step if false", s.id) { t -> addLinkTo(Target(ref.path + (ref.index to false)), t) }
            }
        }
        if (s.type != StepType.LINK && s.type != StepType.GOTO) {
            add(if (s.linkedOnly) "Run in order again" else "Run only when linked", Icon.LINK) {
                closeMenu()
                edit(ref) { it.copy(linkedOnly = !it.linkedOnly) }
                refreshPanel()
            }
        }
        add("Duplicate", Icon.COPY) {
            closeMenu()
            modify(ref.path) { it.add((ref.index + 1).coerceAtMost(it.size), renumber(s)) }
            refreshPanel()
        }
        add("Copy", Icon.COPY) { closeMenu(); clipboard = s; toast("Copied. Use Paste in the + menu.") }
        add("Cut", Icon.COPY) { removeStep(ref, s, cut = true) }
        add("Delete", Icon.CLOSE, TokenInts.danger) { removeStep(ref, s, cut = false) }
        add(if (s.enabled) "Disable" else "Enable", Icon.EYE_OFF) {
            closeMenu()
            edit(ref) { it.copy(enabled = !it.enabled) }
            refreshPanel()
        }
        showMenu(stepTitle(s), rows)
    }

    /** Adds a Run step link into a branch, and offers to make the linked step run only when linked. */
    private fun addLinkTo(target: Target, t: Step) {
        finishAdd(target, Step(StepType.LINK, targetId = t.id))
        askLinkedOnly(t)
    }

    /** Deleting a step that links point to would break them, so say so first. */
    private fun removeStep(ref: Ref, s: Step, cut: Boolean) {
        val n = linksTo(s.id) + flat(s.onSuccess + s.onFail).sumOf { linksTo(it.id) }
        fun doIt() {
            closeMenu()
            if (cut) clipboard = s
            modify(ref.path) { if (ref.index in it.indices) it.removeAt(ref.index) }
            refreshPanel()
        }
        if (n == 0) {
            doIt()
            return
        }
        closeMenu()
        showDialogBox("This step is linked") { box ->
            box.addView(
                label(
                    "$n link${if (n == 1) "" else "s"} point to \"${stepTitle(s)}\". If you ${if (cut) "cut" else "delete"} it, " +
                        "${if (n == 1) "that link" else "those links"} will show as BROKEN and the macro will stop there until you pick another step.",
                    14f, TokenInts.textMuted,
                ),
                lp(8),
            )
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(textButton("CANCEL") { closeMenu() })
            row.addView(textButton(if (cut) "CUT ANYWAY" else "DELETE ANYWAY") { doIt() })
            box.addView(row, lp(10))
        }
    }

    private fun renameDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        showForm("Rename", "A name makes the step easy to find. Leave it empty to go back to the plain description.", { b ->
            toastField = field(b, "Name", s.name, text = true)
        }, { edit(ref) { it.copy(name = toastField?.text?.toString()?.trim().orEmpty()) } })
    }

    private fun editToastDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        showForm("Toast Message", null, { b -> toastField = field(b, "Message", s.text, text = true) },
            { edit(ref) { it.copy(text = toastField?.text?.toString().orEmpty()) } })
    }

    private fun editOffsetDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var dx: EditText? = null
        var dy: EditText? = null
        var rnd: EditText? = null
        showForm(
            "Offset",
            "Offset is the amount of pixels away from the middle of the found object. Positive dx offset to the right, negative dx offset to the left. Positive dy offset to the bottom, negative dy offset to the top. " +
                "Random offset moves every tap a few pixels in a random direction, so no two taps land on exactly the same pixel.",
            { b ->
                dx = field(b, "dx", s.offsetX.toString(), signed = true)
                dy = field(b, "dy", s.offsetY.toString(), signed = true)
                rnd = field(b, "Random offset (px)", s.randomizePx.toString())
            },
            {
                edit(ref) {
                    it.copy(
                        offsetX = dx?.text.toString().toIntOrNull() ?: 0,
                        offsetY = dy?.text.toString().toIntOrNull() ?: 0,
                        randomizePx = (rnd?.text.toString().toIntOrNull() ?: 0).coerceIn(0, 200),
                    )
                }
            },
        )
    }

    private fun editDelayDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var before: EditText? = null
        var after: EditText? = null
        showForm(
            "Delay",
            "Before: wait time before executing the gesture. After: wait time after the gesture has been executed. Total delay time that is too short (< 50ms) might cause the screen to freeze. You can turn off the screen to forcefully stop the macro.",
            { b ->
                before = field(b, "Before (ms)", s.delayMs.toString())
                after = field(b, "After (ms)", s.waitNextMs.toString())
            },
            { edit(ref) { it.copy(delayMs = before?.text.toString().toLongOrNull() ?: 0L, waitNextMs = after?.text.toString().toLongOrNull() ?: 0L) } },
        )
    }

    private fun editClickStyleDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var count: EditText? = null
        var gap: EditText? = null
        var hold: EditText? = null
        var two = s.twoFingers
        showForm(
            "Click Style",
            "Number of clicks happen one after another, with the gap between them. Hold keeps each click pressed for that long (0 is a quick tap). " +
                "Two fingers presses with two fingers at once.",
            { b ->
                val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("Single" to (1 to 0), "Long" to (1 to 600), "Double" to (2 to 0), "Triple" to (3 to 0)).forEach { (name, v) ->
                    presets.addView(textButton(name) {
                        count?.setText(v.first.toString())
                        hold?.setText(v.second.toString())
                    })
                }
                b.addView(presets, lp(2))
                count = field(b, "Number of clicks (1 to 99)", s.clicks.toString())
                gap = field(b, "Gap between clicks (ms)", s.clickGapMs.toString())
                hold = field(b, "Hold each click for (ms, 0 = quick tap)", s.holdMs.toString())
                b.addView(checkBox("Two fingers at once", two) { two = it }, lp(8))
            },
            {
                edit(ref) {
                    it.copy(
                        clicks = (count?.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99),
                        clickGapMs = (gap?.text.toString().toLongOrNull() ?: 90L).coerceIn(20L, 10_000L),
                        holdMs = (hold?.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 59_000L),
                        twoFingers = two,
                    )
                }
            },
        )
    }

    // ---------------------------------------------------------------- hold until a picture appears or disappears

    /** "Hold Until...": choose appear or disappear and a picture; or, if one is set, edit it. */
    private fun holdUntilEntry(ref: Ref) {
        val s = stepAt(ref) ?: return
        if (s.holdUntil != null) {
            openHoldMenu(ref)
            return
        }
        showMenu(
            "Hold until",
            listOf(
                Row("Picture appears", icon = Icon.PICTURE) { closeMenu(); addHoldUntil(ref, true) },
                Row("Picture disappears", icon = Icon.PICTURE) { closeMenu(); addHoldUntil(ref, false) },
                Row("Colour appears", icon = Icon.PENCIL) { holdColor(ref, 0) },
                Row("Colour disappears", icon = Icon.PENCIL) { holdColor(ref, 1) },
                Row("Colour changes", icon = Icon.REDO) { holdColor(ref, 2) },
            ),
        )
    }

    private fun holdColor(ref: Ref, mode: Int) {
        closeMenu()
        addColorCondition(ref, mode) { c ->
            edit(ref) { it.copy(holdUntil = c.copy(label = "Hold ${ref.index + 1}"), clicks = 1) }
            refreshPanel()
        }
    }

    private fun addHoldUntil(ref: Ref, appear: Boolean) {
        newOrExist { box, id, fw, fh ->
            val c = Condition(
                kind = ConditionKind.IMAGE, templateId = id, mustAppear = appear, wholeScreen = true,
                label = "Hold ${ref.index + 1}",
                boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh,
            )
            edit(ref) { it.copy(holdUntil = c, clicks = 1) }
            refreshPanel()
        }
    }

    private fun openHoldMenu(ref: Ref) {
        val s = stepAt(ref) ?: return
        val h = s.holdUntil ?: return
        fun upd(f: (Condition) -> Condition) = edit(ref) { st -> st.copy(holdUntil = st.holdUntil?.let(f)) }
        if (h.kind == ConditionKind.COLOR) {
            val rows = ArrayList<Row>()
            colorRows(rows, h, ::upd)
            rows.add(Row("Edit Longest Hold", icon = Icon.PENCIL) {
                var secs: EditText? = null
                showForm("Longest Hold", "If it has not happened by then, the press is let go and the step counts as FALSE.", { b ->
                    secs = field(b, "Seconds (1 to 55)", (s.holdMaxMs / 1000).toString())
                }, { edit(ref) { it.copy(holdMaxMs = (secs?.text.toString().toLongOrNull() ?: 10L).coerceIn(1L, 55L) * 1000) } })
            })
            rows.add(Row("Remove Hold Until", TokenInts.danger, Icon.CLOSE) { closeMenu(); edit(ref) { it.copy(holdUntil = null) }; refreshPanel() })
            showMenu("Hold until", rows)
            return
        }
        showMenu(
            "Hold until",
            listOf(
                Row("Re-crop Image", icon = Icon.PENCIL) { closeMenu(); recropHold(ref) },
                Row("Change Image", icon = Icon.PENCIL) {
                    closeMenu()
                    startCapture(null) { box, id, fw, fh ->
                        upd { it.copy(templateId = id, boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) }
                        refreshPanel()
                    }
                },
                Row(if (h.mustAppear) "Change to: until it disappears" else "Change to: until it appears", icon = Icon.REDO) {
                    closeMenu(); upd { it.copy(mustAppear = !it.mustAppear) }; refreshPanel()
                },
                Row("Edit Similarity %", icon = Icon.PENCIL) { similarityDialog(h.threshold) { v -> upd { it.copy(threshold = v) } } },
                Row("Edit Detect Location", icon = Icon.PENCIL) { detectLocationDialog(h.wholeScreen) { v -> upd { it.copy(wholeScreen = v) } } },
                Row("Edit Longest Hold", icon = Icon.PENCIL) {
                    var secs: EditText? = null
                    showForm("Longest Hold", "If the picture has not ${if (h.mustAppear) "appeared" else "disappeared"} by then, the press is let go and the step counts as FALSE (its If false steps and links run).", { b ->
                        secs = field(b, "Seconds (1 to 55)", (s.holdMaxMs / 1000).toString())
                    }, { edit(ref) { it.copy(holdMaxMs = (secs?.text.toString().toLongOrNull() ?: 10L).coerceIn(1L, 55L) * 1000) } })
                },
                Row("Test Condition", TokenInts.success, Icon.PLAY) {
                    closeMenu()
                    testMatch(h.templateId, h.boxX, h.boxY, h.boxW, h.boxH, h.capW, h.threshold, h.wholeScreen, h.mustAppear, h.label)
                },
                Row("Remove Hold Until", TokenInts.danger, Icon.CLOSE) {
                    closeMenu(); edit(ref) { it.copy(holdUntil = null) }; refreshPanel()
                },
            ),
        )
    }

    private fun editWaitDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var sec: EditText? = null
        showForm("Wait", null, { b -> sec = field(b, "Seconds", (s.ms / 1000.0).toString(), decimal = true) },
            { edit(ref) { it.copy(ms = ((sec?.text.toString().toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceAtLeast(50)) } })
    }

    private fun editSwipeDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var ms: EditText? = null
        showForm("Swipe Speed", "A longer swipe time is a slower swipe.", { b -> ms = field(b, "Time (ms)", s.swipeMs.toString()) },
            { edit(ref) { it.copy(swipeMs = (ms?.text.toString().toLongOrNull() ?: 300L).coerceIn(100L, 5000L)) } })
    }

    /** "Edit Image": how long to look, what to do when it is not found, and the finder's options. */
    private fun editImageDialog(ref: Ref) {
        val s = stepAt(ref) ?: return
        var secs: EditText? = null
        var skip = s.skipIfMissing
        var flex = s.flexScale
        var all = s.matchAll
        showForm("Edit Image", null, { b ->
            secs = field(b, "Look for (seconds)", (s.timeoutMs / 1000).toString())
            b.addView(checkBox("If it is not found, skip this step instead of stopping", skip) { skip = it }, lp(10))
            b.addView(checkBox("Allow the picture to be a little bigger or smaller", flex) { flex = it }, lp(2))
            b.addView(checkBox("If it shows up more than once, click every one", all) { all = it }, lp(2))
        }, {
            edit(ref) {
                it.copy(
                    timeoutMs = ((secs?.text.toString().toLongOrNull() ?: 10L).coerceIn(1L, 600L)) * 1000,
                    skipIfMissing = skip, flexScale = flex, matchAll = all,
                )
            }
        })
    }

    // ================================================================ pictures: the picture's own menu and conditions

    /** The menu for the picture a Click Image step looks for. */
    private fun openOwnPictureMenu(ref: Ref) {
        val s = stepAt(ref) ?: return
        showMenu(
            "Image",
            listOf(
                Row("Re-crop Image", icon = Icon.PENCIL) { closeMenu(); recropStep(ref) },
                Row("Change Image", icon = Icon.PENCIL) { closeMenu(); changeImage(ref, false) },
                Row("Edit Similarity %", icon = Icon.PENCIL) { similarityDialog(s.threshold) { v -> edit(ref) { it.copy(threshold = v) } } },
                Row("Edit Detect Location", icon = Icon.PENCIL) { detectLocationDialog(s.wholeScreen) { v -> edit(ref) { it.copy(wholeScreen = v) } } },
                Row("Edit Image", icon = Icon.PENCIL) { editImageDialog(ref) },
                Row("Test Condition", TokenInts.success, Icon.PLAY) {
                    closeMenu()
                    testMatch(s.templateId, s.boxX, s.boxY, s.boxW, s.boxH, s.capW, s.threshold, s.wholeScreen, true, "the picture")
                },
            ),
        )
    }

    private fun openConditionMenu(ref: Ref, j: Int) {
        val s = stepAt(ref) ?: return
        val c = s.conditions.getOrNull(j) ?: return
        fun upd(f: (Condition) -> Condition) = edit(ref) { st -> st.copy(conditions = st.conditions.mapIndexed { k, x -> if (k == j) f(x) else x }) }
        val rows = ArrayList<Row>()
        rows.add(Row(if (c.enabled) "Turn Off (keep it)" else "Turn On", icon = Icon.CHECK) {
            closeMenu(); upd { it.copy(enabled = !it.enabled) }; refreshPanel()
        })
        when (c.kind) {
            ConditionKind.COLOR -> colorRows(rows, c, ::upd)
            ConditionKind.TEXT -> textRows(rows, c, ::upd)
            ConditionKind.IMAGE -> {
                rows.add(Row("Re-crop Image", icon = Icon.PENCIL) { closeMenu(); recropCondition(ref, j) })
                rows.add(Row("Change Image", icon = Icon.PENCIL) { closeMenu(); changeConditionImage(ref, j, true) })
                rows.add(Row("Edit Similarity %", icon = Icon.PENCIL) { similarityDialog(c.threshold) { v -> upd { it.copy(threshold = v) } } })
                rows.add(Row("Edit Detect Location", icon = Icon.PENCIL) { detectLocationDialog(c.wholeScreen) { v -> upd { it.copy(wholeScreen = v) } } })
                rows.add(Row(if (c.mustAppear) "Change to Not appear" else "Change to Appears", icon = Icon.REDO) {
                    closeMenu(); upd { it.copy(mustAppear = !it.mustAppear) }; refreshPanel()
                })
                rows.add(Row("Test Condition", TokenInts.success, Icon.PLAY) {
                    closeMenu()
                    testMatch(c.templateId, c.boxX, c.boxY, c.boxW, c.boxH, c.capW, c.threshold, c.wholeScreen, c.mustAppear, c.label)
                })
            }
            ConditionKind.ACTION_RESULT -> rows.add(Row("Change to " + if (c.resultOk) "Failed" else "Success", icon = Icon.REDO) {
                closeMenu(); upd { it.copy(resultOk = !it.resultOk) }; refreshPanel()
            })
            ConditionKind.RANDOM -> rows.add(Row("Edit Chance %", icon = Icon.PENCIL) {
                var pct: EditText? = null
                showForm("Random True", "The step runs this percent of the time.", { b -> pct = field(b, "Percent", c.percent.toString()) },
                    { upd { it.copy(percent = (pct?.text.toString().toIntOrNull() ?: 50).coerceIn(1, 99)) } })
            })
            ConditionKind.ONCE -> Unit
            ConditionKind.COUNTER -> {
                rows.add(Row("Change Counter", icon = Icon.PENCIL) { closeMenu(); pickCounter("Which counter?") { n -> upd { it.copy(text = n) }; refreshPanel() } })
                rows.add(Row("Edit Number", icon = Icon.PENCIL) {
                    var f: EditText? = null
                    showForm("Number", null, { b -> f = field(b, "Number", c.percent.toString(), signed = true) }, { upd { it.copy(percent = f?.text.toString().toIntOrNull() ?: c.percent) } })
                })
                rows.add(Row(if (c.mustAppear) "Change to: below the number" else "Change to: at least the number", icon = Icon.REDO) {
                    closeMenu(); upd { it.copy(mustAppear = !it.mustAppear) }; refreshPanel()
                })
            }
        }
        rows.add(Row("Delete", TokenInts.danger, Icon.CLOSE) {
            closeMenu()
            edit(ref) { st -> st.copy(conditions = st.conditions.filterIndexed { k, _ -> k != j }) }
            refreshPanel()
        })
        showMenu("Condition", rows)
    }

    private fun similarityDialog(current: Int, onSave: (Int) -> Unit) {
        var f: EditText? = null
        showForm("Similarity %", "How alike the picture on screen must be to the saved picture. Higher is stricter. 70 to 99.", { b ->
            f = field(b, "Similarity (%)", current.toString())
        }, { onSave((f?.text.toString().toIntOrNull() ?: current).coerceIn(70, 99)) })
    }

    private fun detectLocationDialog(whole: Boolean, onSave: (Boolean) -> Unit) {
        showChoice("Detect Location", listOf("Captured location (look near where it was captured)", "Whole screen"), if (whole) 1 else 0) { k ->
            onSave(k == 1)
        }
    }

    private fun openConditionPicker(ref: Ref) {
        closeMenu()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            background = Ui.rounded(0xFF2B2E36.toInt(), dp(16).toFloat())
        }
        box.addView(label("Condition", 20f, TokenInts.text))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sectionTitle(body, "Detection")
        gridRows(
            body,
            listOf(
                Triple(Icon.PICTURE, "Image Appears") { closeMenu(); addImageCondition(ref, true) },
                Triple(Icon.PICTURE, "Image Not Appear") { closeMenu(); addImageCondition(ref, false) },
                Triple(Icon.PENCIL, "Colour Appears") { closeMenu(); addColorCondition(ref, 0) { c -> edit(ref) { it.copy(conditions = it.conditions + c) }; refreshPanel() } },
                Triple(Icon.PENCIL, "Colour Not Appear") { closeMenu(); addColorCondition(ref, 1) { c -> edit(ref) { it.copy(conditions = it.conditions + c) }; refreshPanel() } },
                Triple(Icon.REDO, "Colour Changes") { closeMenu(); addColorCondition(ref, 2) { c -> edit(ref) { it.copy(conditions = it.conditions + c) }; refreshPanel() } },
                Triple(Icon.PENCIL, "Text Appears") { closeMenu(); addTextCondition(true) { c -> edit(ref) { it.copy(conditions = it.conditions + c) }; refreshPanel() } },
                Triple(Icon.PENCIL, "Text Not Appear") { closeMenu(); addTextCondition(false) { c -> edit(ref) { it.copy(conditions = it.conditions + c) }; refreshPanel() } },
            ),
        )
        sectionTitle(body, "Macro")
        gridRows(
            body,
            listOf(
                Triple(Icon.REDO, "Action Result") {
                    showChoice("Action Result", listOf("The step before worked (Success)", "The step before did not work (Failed)"), 0) { k ->
                        edit(ref) { it.copy(conditions = it.conditions + Condition(kind = ConditionKind.ACTION_RESULT, resultOk = k == 0)) }
                    }
                },
                Triple(Icon.GEAR, "Random True") {
                    var pct: EditText? = null
                    showForm("Random True", "The step runs this percent of the time.", { b -> pct = field(b, "Percent", "50") },
                        { edit(ref) { it.copy(conditions = it.conditions + Condition(kind = ConditionKind.RANDOM, percent = (pct?.text.toString().toIntOrNull() ?: 50).coerceIn(1, 99))) } })
                },
                Triple(Icon.CHECK, "Once True") {
                    closeMenu()
                    edit(ref) { it.copy(conditions = it.conditions + Condition(kind = ConditionKind.ONCE)) }
                    refreshPanel()
                },
                Triple(Icon.PLUS, "Counter At Least") { closeMenu(); addCounterCondition(ref, true) },
                Triple(Icon.PLUS, "Counter Below") { closeMenu(); addCounterCondition(ref, false) },
            ),
        )
        box.addView(MaxHeightScrollView(ctx, minOf((screenH * 0.7).toInt(), screenH - dp(230))).apply { addView(body) })
        box.addView(textButton("CANCEL") { closeMenu() }.apply { gravity = Gravity.END }, lp(6))
        val w = (screenW * 0.94).toInt()
        val p = params(w, WindowManager.LayoutParams.WRAP_CONTENT, (screenW - w) / 2, dp(90), outside = true)
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false }
        wm.addView(box, p)
        menu = box
        applyHidden()
    }

    /** mode 0 = the colour appears, 1 = it does not appear, 2 = the colour in the area changes. */
    private fun addColorCondition(ref: Ref, mode: Int, onDone: (Condition) -> Unit) {
        val hint = if (mode == 2) "the area to watch" else "the colour to look for"
        toast("Move and resize the box over $hint, then tap the tick.")
        startCapture(null, 1) { box, hex, fw, fh ->
            val col = runCatching { Color.parseColor(hex) }.getOrDefault(0)
            onDone(
                Condition(
                    kind = ConditionKind.COLOR, color = col, mustAppear = mode != 1, changes = mode == 2,
                    wholeScreen = mode != 2, tolerance = if (mode == 2) 12 else 20,
                    label = if (mode == 2) "Colour change" else "Colour ${ScreenCheck.hex(col)}",
                    boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh,
                ),
            )
        }
    }

    private fun addTextCondition(appear: Boolean, onDone: (Condition) -> Unit) {
        toast("Move and resize the box over the words, then tap the tick.")
        startCapture(null, 2) { box, _, fw, fh ->
            var words: EditText? = null
            showForm("Words to look for", "Type the words as they appear on screen. Capital letters and spaces do not matter.", { b ->
                words = field(b, "Words", "", text = true)
            }, {
                val w = words?.text.toString().trim()
                if (w.isNotEmpty()) {
                    onDone(
                        Condition(
                            kind = ConditionKind.TEXT, text = w, mustAppear = appear, wholeScreen = false, label = "Text $w",
                            boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh,
                        ),
                    )
                }
            })
        }
    }

    /** The menu rows for a colour condition or colour hold-until; [upd] changes it wherever it is kept. */
    private fun colorRows(rows: MutableList<Row>, c: Condition, upd: ((Condition) -> Condition) -> Unit) {
        if (!c.changes) rows.add(Row("Pick Colour Again", icon = Icon.PENCIL) {
            closeMenu()
            startCapture(if (c.boxW > 0) Rect(c.boxX, c.boxY, c.boxX + c.boxW, c.boxY + c.boxH) else null, 1) { box, hex, fw, fh ->
                val col = runCatching { Color.parseColor(hex) }.getOrDefault(c.color)
                upd { it.copy(color = col, label = "Colour ${ScreenCheck.hex(col)}", boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) }
                refreshPanel()
            }
        }) else rows.add(Row("Move Area", icon = Icon.PENCIL) {
            closeMenu()
            startCapture(if (c.boxW > 0) Rect(c.boxX, c.boxY, c.boxX + c.boxW, c.boxY + c.boxH) else null, 1) { box, _, fw, fh ->
                upd { it.copy(boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) }
                refreshPanel()
            }
        })
        rows.add(Row("Edit Colour Match %", icon = Icon.PENCIL) {
            var f: EditText? = null
            showForm("Colour match", "How far a colour may be from the saved one and still count. Lower is stricter. 1 to 60.", { b ->
                f = field(b, "Tolerance (%)", c.tolerance.toString())
            }, { upd { it.copy(tolerance = (f?.text.toString().toIntOrNull() ?: c.tolerance).coerceIn(1, 60)) } })
        })
        if (!c.changes) {
            rows.add(Row("Edit Detect Location", icon = Icon.PENCIL) { detectLocationDialog(c.wholeScreen) { v -> upd { it.copy(wholeScreen = v) } } })
            rows.add(Row(if (c.mustAppear) "Change to Not appear" else "Change to Appears", icon = Icon.REDO) {
                closeMenu(); upd { it.copy(mustAppear = !it.mustAppear) }; refreshPanel()
            })
        }
        rows.add(Row("Test Condition", TokenInts.success, Icon.PLAY) { closeMenu(); testScreen(c) })
    }

    private fun textRows(rows: MutableList<Row>, c: Condition, upd: ((Condition) -> Condition) -> Unit) {
        rows.add(Row("Edit Words", icon = Icon.PENCIL) {
            var words: EditText? = null
            showForm("Words to look for", "Capital letters and spaces do not matter.", { b -> words = field(b, "Words", c.text, text = true) },
                { val w = words?.text.toString().trim(); if (w.isNotEmpty()) upd { it.copy(text = w, label = "Text $w") } })
        })
        rows.add(Row("Move Area", icon = Icon.PENCIL) {
            closeMenu()
            startCapture(if (c.boxW > 0) Rect(c.boxX, c.boxY, c.boxX + c.boxW, c.boxY + c.boxH) else null, 2) { box, _, fw, fh ->
                upd { it.copy(boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) }
                refreshPanel()
            }
        })
        rows.add(Row("Edit Detect Location", icon = Icon.PENCIL) { detectLocationDialog(c.wholeScreen) { v -> upd { it.copy(wholeScreen = v) } } })
        rows.add(Row(if (c.mustAppear) "Change to Not appear" else "Change to Appears", icon = Icon.REDO) {
            closeMenu(); upd { it.copy(mustAppear = !it.mustAppear) }; refreshPanel()
        })
        rows.add(Row("Test Condition", TokenInts.success, Icon.PLAY) { closeMenu(); testScreen(c) })
    }

    /** Looks at the screen once and says whether a colour or text condition holds right now. */
    private fun testScreen(c: Condition) {
        if (!CaptureService.running) {
            toast("Screen capture is off. Open MacroLoop, press Edit Mode, and tap Start.")
            return
        }
        val svc = MacroService.instance ?: return
        scope.launch {
            setHidden(true)
            CaptureService.instance?.fast = true
            delay(450)
            val msg = svc.testCondition(c)
            CaptureService.instance?.fast = false
            setHidden(false)
            toast(msg)
        }
    }

    private fun addImageCondition(ref: Ref, appear: Boolean) {
        newOrExist { box, id, fw, fh ->
            val n = (stepAt(ref)?.conditions?.size ?: 0)
            val c = Condition(
                kind = ConditionKind.IMAGE, templateId = id, mustAppear = appear, wholeScreen = true,
                label = "Condition ${ref.index + 1}${('a' + n)}",
                boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh,
            )
            edit(ref) { it.copy(conditions = it.conditions + c) }
            refreshPanel()
        }
    }

    // ================================================================ the picture editor (re-crop and tap point)

    /**
     * Opens the saved picture big. Drag its corners to crop it smaller, and (for a picture a step taps) drag the dot
     * to choose where in the picture the tap lands. Saving keeps the new crop as a new picture file.
     */
    private fun openPictureEditor(templateId: String, withDot: Boolean, offX: Int, offY: Int, onSave: (String, Rect, Int, Int) -> Unit) {
        val bmp = TemplateStore.load(templateId)
        if (bmp == null) {
            toast("This picture is missing. Use Change Image.")
            return
        }
        showDialogBox("Image") { box ->
            val view = PicCropView(ctx, bmp, withDot, android.graphics.PointF(bmp.width / 2f + offX, bmp.height / 2f + offY))
            box.addView(view, lp(10).apply { height = dp(280) })
            box.addView(
                label(if (withDot) "Drag the corners to crop the picture smaller. Drag the dot to choose where the tap lands." else "Drag the corners to crop the picture smaller.", 12f, TokenInts.textMuted),
                lp(8),
            )
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(textButton("CANCEL") { closeMenu() })
            row.addView(textButton("RESET") { view.reset() })
            row.addView(textButton("SAVE") {
                val r = view.cropRect()
                val d = view.dotPoint()
                closeMenu()
                val whole = r.left == 0 && r.top == 0 && r.right == bmp.width && r.bottom == bmp.height
                val id = if (whole) templateId else TemplateStore.save(Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()))
                val ox = if (withDot) Math.round(d.x - (r.left + r.width() / 2f)) else 0
                val oy = if (withDot) Math.round(d.y - (r.top + r.height() / 2f)) else 0
                onSave(id, r, ox, oy)
                dirty = true
                refreshPanelIfOpen()
            })
            box.addView(row, lp(8))
        }
    }

    private fun recropStep(ref: Ref) {
        val s = stepAt(ref) ?: return
        openPictureEditor(s.templateId, true, s.offsetX, s.offsetY) { id, r, ox, oy ->
            edit(ref) { it.copy(templateId = id, boxX = it.boxX + r.left, boxY = it.boxY + r.top, boxW = r.width(), boxH = r.height(), offsetX = ox, offsetY = oy) }
        }
    }

    private fun recropCondition(ref: Ref, j: Int) {
        val c = stepAt(ref)?.conditions?.getOrNull(j) ?: return
        openPictureEditor(c.templateId, false, 0, 0) { id, r, _, _ ->
            edit(ref) { st ->
                st.copy(conditions = st.conditions.mapIndexed { k, x ->
                    if (k == j) x.copy(templateId = id, boxX = x.boxX + r.left, boxY = x.boxY + r.top, boxW = r.width(), boxH = r.height()) else x
                })
            }
        }
    }

    private fun recropHold(ref: Ref) {
        val h = stepAt(ref)?.holdUntil ?: return
        openPictureEditor(h.templateId, false, 0, 0) { id, r, _, _ ->
            edit(ref) { st ->
                st.copy(holdUntil = st.holdUntil?.copy(templateId = id, boxX = h.boxX + r.left, boxY = h.boxY + r.top, boxW = r.width(), boxH = r.height()))
            }
        }
    }

    // ================================================================ macro menu: Main Job / Watchers / Stuck rescue, counters, schedule

    private fun openMacroMenu() {
        val rows = ArrayList<Row>()
        rows.add(Row("Show"))
        fun sec(k: Int, name: String) = rows.add(Row((if (section == k) "✓  " else "     ") + name, icon = Icon.LIST) {
            closeMenu(); section = k; panelScrollY = 0; refreshPanel()
        })
        sec(0, "Main Job")
        sec(1, "Watchers (${watchers.size})")
        sec(2, "Stuck rescue (${stuck.size})")
        rows.add(Row("Settings"))
        rows.add(Row("Counters (${counters.size})", icon = Icon.PLUS) { openCounters() })
        rows.add(Row("Stuck check: " + (if (stuckSec > 0) "$stuckSec s" else "off"), icon = Icon.GEAR) { stuckDialog() })
        rows.add(Row("Schedule: " + (MacroScheduler.describe(current()) ?: "off"), icon = Icon.PLAY) { scheduleDialog() })
        rows.add(Row("Help"))
        rows.add(Row("Help topics", icon = Icon.LIST) { openHelpList() })
        showMenu("Macro", rows, anchor = dp(24) to dp(70))
    }

    private fun addWatcher() {
        val n = watchers.size + 1
        modify(emptyList()) { it.add(Step(StepType.WATCH, name = "Watcher $n")) }
        refreshPanel()
        openConditionPicker(Ref(emptyList(), watchers.size - 1))
    }

    private fun openWatchMenu(ref: Ref, s: Step) {
        val rows = ArrayList<Row>()
        rows.add(Row("Add Condition", icon = Icon.PLUS) { openConditionPicker(ref) })
        rows.add(Row("Add step if true", TokenInts.success, Icon.PLUS) { openActionPicker(Target(ref.path + (ref.index to true))) })
        rows.add(Row("Link a step if true", TokenInts.success, Icon.LINK) {
            closeMenu()
            openStepPicker("Run Step if true", s.id) { t -> addLinkTo(Target(ref.path + (ref.index to true)), t) }
        })
        rows.add(Row("Rename", icon = Icon.PENCIL) { renameDialog(ref) })
        rows.add(Row("Duplicate", icon = Icon.COPY) {
            closeMenu()
            modify(ref.path) { it.add((ref.index + 1).coerceAtMost(it.size), renumber(s)) }
            refreshPanel()
        })
        rows.add(Row("Delete", TokenInts.danger, Icon.CLOSE) { removeStep(ref, s, cut = false) })
        rows.add(Row(if (s.enabled) "Disable" else "Enable", icon = Icon.EYE_OFF) {
            closeMenu(); edit(ref) { it.copy(enabled = !it.enabled) }; refreshPanel()
        })
        showMenu(stepTitle(s), rows)
    }

    // ---------------------------------------------------------------- counters

    private fun counterNote(c: CounterDef) =
        if (c.keep) "kept between runs, now ${CounterStore.load(ctx, macroId, c.name)}" else "starts at 0 each run"

    private fun openCounters() {
        val rows = ArrayList<Row>()
        rows.add(Row("Counters are numbers. Add to one in a step, and use it in a condition (for example: stop when it is at least 10)."))
        counters.forEach { c -> rows.add(Row("${c.name}   (${counterNote(c)})", icon = Icon.PENCIL) { counterMenu(c) }) }
        rows.add(Row("New counter", icon = Icon.PLUS) { newCounter { later { openCounters() } } })
        showMenu("Counters", rows)
    }

    private fun counterMenu(c: CounterDef) {
        val rows = ArrayList<Row>()
        rows.add(Row(if (c.keep) "Start at 0 each run" else "Keep between runs (and days)", icon = Icon.REDO) {
            counters = counters.map { if (it.name == c.name) it.copy(keep = !it.keep) else it }
            dirty = true
            openCounters()
        })
        if (c.keep) rows.add(Row("Set the kept number back to 0", icon = Icon.REDO) {
            CounterStore.save(ctx, macroId, c.name, 0)
            toast("${c.name} is 0")
            openCounters()
        })
        rows.add(Row("Delete", TokenInts.danger, Icon.CLOSE) {
            val uses = everyStep().count { st -> (st.type == StepType.COUNTER_ADD || st.type == StepType.COUNTER_RESET) && st.text == c.name || st.conditions.any { it.kind == ConditionKind.COUNTER && it.text == c.name } }
            counters = counters.filter { it.name != c.name }
            dirty = true
            if (uses > 0) toast("$uses step(s) still use ${c.name}. It works as a counter that starts at 0.")
            openCounters()
        })
        showMenu(c.name, rows)
    }

    private fun newCounter(onDone: (String) -> Unit) {
        var name: EditText? = null
        var keep = false
        showForm("New counter", "Letters and numbers, for example: claims", { b ->
            name = field(b, "Name", "", text = true)
            b.addView(checkBox("Keep the number between runs (and days)", false) { keep = it }, lp(8))
        }, {
            val n = name?.text.toString().trim().take(24)
            when {
                n.isEmpty() -> toast("A counter needs a name.")
                counters.any { it.name.equals(n, ignoreCase = true) } -> toast("There is already a counter named $n.")
                else -> {
                    counters = counters + CounterDef(n, keep)
                    dirty = true
                    onDone(n)
                }
            }
        })
    }

    /** Pick one of the macro's counters, or make a new one. */
    private fun pickCounter(title: String, onPick: (String) -> Unit) {
        val rows = ArrayList<Row>()
        counters.forEach { c -> rows.add(Row(c.name, icon = Icon.PLUS) { closeMenu(); onPick(c.name) }) }
        rows.add(Row("New counter", icon = Icon.PLUS) { newCounter { n -> later { onPick(n) } } })
        showMenu(title, rows)
    }

    private fun addCounterCondition(ref: Ref, atLeast: Boolean) {
        pickCounter(if (atLeast) "Counter at least..." else "Counter below...") { name ->
            var f: EditText? = null
            showForm(if (atLeast) "$name is at least" else "$name is below", null, { b -> f = field(b, "Number", "5", signed = true) }, {
                val n = f?.text.toString().toIntOrNull() ?: 5
                edit(ref) { it.copy(conditions = it.conditions + Condition(kind = ConditionKind.COUNTER, text = name, percent = n, mustAppear = atLeast)) }
            })
        }
    }

    // ---------------------------------------------------------------- stuck check and schedule

    private fun stuckDialog() {
        var f: EditText? = null
        showForm(
            "Stuck check",
            "If the screen does not change for this many seconds, the Stuck rescue steps run (none = the macro stops). " +
                "It looks for a frozen screen, so a game with a moving background is never counted as stuck by mistake. 0 turns it off. Needs screen capture.",
            { b -> f = field(b, "Seconds (0 = off, otherwise 10 to 600)", stuckSec.toString()) },
            {
                val v = f?.text.toString().toIntOrNull() ?: 0
                stuckSec = if (v <= 0) 0 else v.coerceIn(10, 600)
                dirty = true
            },
        )
    }

    private fun scheduleDialog() {
        val sc = schedule
        var on = sc.enabled
        var days = sc.days
        var times: EditText? = null
        var dur: EditText? = null
        showForm(
            "Schedule",
            "Starts the macro by itself. The phone must be unlocked, Accessibility on, and screen capture started (if the macro looks at the screen). Applies when you Save.",
            { b ->
                b.addView(checkBox("Start by itself", on) { on = it }, lp(6))
                times = field(b, "Times of day, 24 hours (for example 06:00, 18:30)", MacroScheduler.timesText(sc.minutes), text = true)
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                b.addView(label("Days", 12f, TokenInts.textMuted), lp(10))
                names.chunked(4).forEachIndexed { row, chunk ->
                    val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    chunk.forEachIndexed { k, name ->
                        val bit = 1 shl (row * 4 + k)
                        line.addView(checkBox(name, days and bit != 0) { c -> days = if (c) days or bit else days and bit.inv() }.apply { textSize = 14f },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    b.addView(line)
                }
                dur = field(b, "Stop after (minutes, 0 = when it finishes)", sc.durationMin.toString())
            },
            {
                val parsed = MacroScheduler.parseTimes(times?.text.toString())
                if (on && (parsed == null || parsed.isEmpty())) {
                    toast("Times look like 06:00, 18:30. Schedule not changed.")
                } else if (on && days and 127 == 0) {
                    toast("Pick at least one day. Schedule not changed.")
                } else {
                    schedule = Schedule(on, parsed ?: emptyList(), days and 127, (dur?.text.toString().toIntOrNull() ?: 0).coerceIn(0, 1440))
                    dirty = true
                }
            },
        )
    }

    // ================================================================ placing a picture: the capture box

    private fun changeImage(ref: Ref, keepBox: Boolean = true) {
        val s = stepAt(ref) ?: return
        val start = if (keepBox && s.boxW > 0) Rect(s.boxX, s.boxY, s.boxX + s.boxW, s.boxY + s.boxH) else null
        startCapture(start) { box, id, fw, fh ->
            edit(ref) { it.copy(templateId = id, boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) }
            refreshPanel()
        }
    }

    private fun changeConditionImage(ref: Ref, j: Int, fresh: Boolean = false) {
        val c = stepAt(ref)?.conditions?.getOrNull(j) ?: return
        val start = if (!fresh && c.boxW > 0) Rect(c.boxX, c.boxY, c.boxX + c.boxW, c.boxY + c.boxH) else null
        startCapture(start) { box, id, fw, fh ->
            edit(ref) { st ->
                st.copy(conditions = st.conditions.mapIndexed { k, x ->
                    if (k == j) x.copy(templateId = id, boxX = box.left, boxY = box.top, boxW = box.width(), boxH = box.height(), capW = fw, capH = fh) else x
                })
            }
            refreshPanel()
        }
    }

    /** The New / Exist choice. New draws a box on the live screen. Exist reuses a picture already in this macro. */
    private fun newOrExist(onDone: (Rect, String, Int, Int) -> Unit) {
        closeMenu()
        showDialogBox("Image") { box ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun big(glyph: String, text: String, act: () -> Unit): View {
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { act() }
                }
                col.addView(
                    label(glyph, 40f, TokenInts.onPrimary).apply {
                        gravity = Gravity.CENTER
                        background = Ui.rounded(TokenInts.primary, dp(14).toFloat())
                    },
                    LinearLayout.LayoutParams(dp(110), dp(100)),
                )
                col.addView(label(text, 16f, TokenInts.text).apply { setPadding(0, dp(6), 0, 0) })
                return col
            }
            row.addView(big("+", "New") { closeMenu(); startCapture(null, 0, onDone) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(big("▤", "Exist") { openExistPicker(onDone) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row, lp(14))
        }
    }

    private fun openExistPicker(onDone: (Rect, String, Int, Int) -> Unit) {
        data class Pic(val id: String, val box: Rect, val w: Int, val h: Int)
        val pics = LinkedHashMap<String, Pic>()
        for (s in everyStep()) {
            if (s.type == StepType.CLICK_IMAGE && s.templateId.isNotEmpty()) pics.putIfAbsent(s.templateId, Pic(s.templateId, Rect(s.boxX, s.boxY, s.boxX + s.boxW, s.boxY + s.boxH), s.capW, s.capH))
            for (c in s.conditions) if (c.kind == ConditionKind.IMAGE && c.templateId.isNotEmpty()) {
                pics.putIfAbsent(c.templateId, Pic(c.templateId, Rect(c.boxX, c.boxY, c.boxX + c.boxW, c.boxY + c.boxH), c.capW, c.capH))
            }
        }
        closeMenu()
        showDialogBox("Exist") { box ->
            if (pics.isEmpty()) {
                box.addView(label("There are no pictures in this macro yet. Use New.", 14f, TokenInts.textMuted), lp(8))
            } else {
                val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                pics.values.toList().chunked(3).forEach { chunk ->
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    chunk.forEach { p ->
                        val bmp = TemplateStore.load(p.id)
                        val img = ImageView(ctx).apply {
                            if (bmp != null) setImageBitmap(bmp)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(TokenInts.surfaceHigh)
                            isClickable = true
                            setOnClickListener { closeMenu(); onDone(p.box, p.id, p.w, p.h) }
                        }
                        row.addView(img, LinearLayout.LayoutParams(0, dp(70), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                    }
                    repeat(3 - chunk.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, dp(70), 1f)) }
                    grid.addView(row)
                }
                box.addView(MaxHeightScrollView(ctx, minOf((screenH * 0.5).toInt(), screenH - dp(270))).apply { addView(grid) }, lp(8))
            }
            box.addView(textButton("CANCEL") { closeMenu() }.apply { gravity = Gravity.END }, lp(6))
        }
    }

    private var capInfo: TextView? = null
    private var capBoxLp: WindowManager.LayoutParams? = null

    /**
     * Shows a box on the live screen and a small X Y W H panel with redo, cancel and confirm.
     * Only this box is on screen, the panel moves to the far side from the box (and can be dragged),
     * and Cancel always works. On confirm, the box is hidden, the screen is looked at, and the crop is saved.
     */
    private var capMode = 0     // 0 = save a picture, 1 = sample a colour, 2 = just an area

    private fun startCapture(initial: Rect?, mode: Int = 0, onDone: (Rect, String, Int, Int) -> Unit) {
        capMode = mode
        if (!CaptureService.running) {
            // Ask for screen capture right here; the editor stays where it was.
            toast("Screen capture is off. Tap Start on the prompt, then try again.")
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_ASK_CAPTURE, true),
            )
            refreshPanelIfOpen()
            return
        }
        closeMenu()
        rm(panel); panel = null; panelScroll = null
        removeCapture()
        val w0 = dp(55)
        val h0 = dp(40)
        val start = initial ?: Rect((screenW - w0) / 2, (screenH - h0) / 2, (screenW + w0) / 2, (screenH + h0) / 2)
        val lpBox = params(start.width(), start.height(), start.left, start.top).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        capBoxLp = lpBox
        val marker = MarkerView(ctx, MarkerView.Kind.PICTURE, "", TokenInts.primary, wm, lpBox, { onCapChanged() }, { })
        wm.addView(marker, lpBox)
        capBox = marker

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.rounded(0xF2111318.toInt(), dp(14).toFloat())
        }
        val info = label("", 14f, TokenInts.text).apply { setPadding(dp(4), 0, dp(10), 0) }
        capInfo = info
        bar.addView(info)
        fun icon(i: Icon, color: Int, help: String, act: () -> Unit) = IconBtn(ctx, i, color, 44) { act() }.also {
            holdHelp(it, help)
            bar.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        }
        icon(Icon.REDO, TokenInts.primary, "btn_redo") {
            val b = capBoxLp ?: return@icon
            b.x = start.left; b.y = start.top; b.width = start.width(); b.height = start.height()
            runCatching { wm.updateViewLayout(capBox, b) }
            onCapChanged()
        }
        icon(Icon.CLOSE, TokenInts.danger, "btn_cancel") { cancelCapture() }
        icon(Icon.CHECK, TokenInts.success, "btn_ok") { confirmCapture(onDone) }
        val lpBar = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, dp(10), screenH - dp(190))
        makeDraggable(info, bar, lpBar)
        wm.addView(bar, lpBar)
        capPanel = bar
        capBarLp = lpBar
        onCapChanged()
        applyHidden()
    }

    private var capBarLp: WindowManager.LayoutParams? = null
    private var capBarMoved = false

    private fun onCapChanged() {
        val b = capBoxLp ?: return
        capInfo?.text = "X: ${b.x}  Y: ${b.y}\nW: ${b.width}  H: ${b.height}"
        // keep the panel on the side of the screen away from the box, unless the user moved it
        val bar = capBarLp ?: return
        val centerY = b.y + b.height / 2
        val wantTop = centerY > screenH / 2
        val targetY = if (wantTop) dp(60) else screenH - dp(190)
        if (kotlin.math.abs(bar.y - targetY) > dp(100) && !capBarMoved) {
            bar.y = targetY
            runCatching { wm.updateViewLayout(capPanel, bar) }
        }
    }

    private fun removeCapture() {
        rm(capBox); capBox = null
        rm(capPanel); capPanel = null
        capInfo = null
        capBoxLp = null
        capBarLp = null
        capBarMoved = false
    }

    private fun cancelCapture() {
        removeCapture()
        refreshPanel()
    }

    private fun confirmCapture(onDone: (Rect, String, Int, Int) -> Unit) {
        val b = capBoxLp ?: return
        val region = Rect(b.x, b.y, b.x + b.width, b.y + b.height)
        capBox?.visibility = View.INVISIBLE
        capPanel?.visibility = View.INVISIBLE
        scope.launch {
            CaptureService.instance?.fast = true
            delay(450)
            var frame = CaptureService.grabFresh(SystemClock.elapsedRealtime() - 100, 400)
            // A flat white (or flat black) picture means the screen was not ready: look again, up to 4 times.
            var tries = 0
            while (frame != null && ScreenCheck.isBlank(frame) && tries < 4) {
                tries++
                delay(350)
                frame = CaptureService.grabFresh(SystemClock.elapsedRealtime() - 100, 500)
            }
            CaptureService.instance?.fast = false
            if (frame == null) {
                toast("Could not see the screen. Try again.")
                capBox?.visibility = View.VISIBLE
                capPanel?.visibility = View.VISIBLE
                return@launch
            }
            val x = region.left.coerceIn(0, frame.width - 2)
            val y = region.top.coerceIn(0, frame.height - 2)
            val w = region.width().coerceIn(2, frame.width - x)
            val h = region.height().coerceIn(2, frame.height - y)
            val crop = Bitmap.createBitmap(frame, x, y, w, h)
            val id = when (capMode) {
                0 -> TemplateStore.save(crop)
                1 -> ScreenCheck.hex(ScreenCheck.meanColor(crop, Rect(0, 0, w, h)))
                else -> ""
            }
            removeCapture()
            onDone(Rect(x, y, x + w, y + h), id, frame.width, frame.height)
            dirty = true
            refreshPanelIfOpen()
        }
    }

    // ================================================================ picking a spot, and drawing a swipe

    private fun startOverlay(view: View) {
        rm(overlay)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        rm(panel); panel = null; panelScroll = null
        closeMenu()
        wm.addView(view, p)
        overlay = view
    }

    private fun endOverlay() {
        rm(overlay)
        overlay = null
        refreshPanel()
    }

    private fun overlayFrame(hint: String): FrameLayout {
        val layer = FrameLayout(ctx).apply { setBackgroundColor(TokenInts.scrim) }
        layer.addView(
            label(hint, 20f, TokenInts.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(32), 0, dp(32), 0)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        layer.addView(
            Ui.button(ctx, "Cancel", TokenInts.surfaceHigh, TokenInts.text) { endOverlay() },
            FrameLayout.LayoutParams(dp(160), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(96) },
        )
        return layer
    }

    private fun pickSpot(hint: String, onPick: (Int, Int) -> Unit) {
        val layer = overlayFrame(hint)
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                rm(overlay); overlay = null
                onPick(x, y)
            }
            true
        }
        startOverlay(layer)
    }

    private fun pickSwipe(onPick: (Int, Int, Int, Int) -> Unit) {
        val layer = overlayFrame("Drag your finger from where the swipe starts to where it ends")
        val line = LinesView(ctx)
        layer.addView(line, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        var sx = 0f
        var sy = 0f
        layer.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_MOVE -> line.lines = listOf(floatArrayOf(sx, sy, e.rawX, e.rawY))
                MotionEvent.ACTION_UP -> {
                    val ex = e.rawX
                    val ey = e.rawY
                    if (kotlin.math.abs(ex - sx) + kotlin.math.abs(ey - sy) < dp(30)) {
                        toast("Drag a longer line.")
                        line.lines = emptyList()
                    } else {
                        rm(overlay); overlay = null
                        onPick(sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt())
                    }
                }
            }
            true
        }
        startOverlay(layer)
    }

    private fun moveSpot(ref: Ref) = pickSpot("Tap the new spot") { x, y -> edit(ref) { it.copy(x = x, y = y) }; refreshPanel() }

    private fun redrawSwipe(ref: Ref) = pickSwipe { x1, y1, x2, y2 -> edit(ref) { it.copy(x = x1, y = y1, x2 = x2, y2 = y2) }; refreshPanel() }

    // ================================================================ tests

    /** Looks for one picture right now and shows where it is, with the score and the time it took. */
    private fun testMatch(templateId: String, bx: Int, by: Int, bw: Int, bh: Int, capW: Int, threshold: Int, whole: Boolean, mustAppear: Boolean, name: String) {
        val t = TemplateStore.load(templateId)
        if (t == null) {
            toast("This picture is missing. Use Change Image.")
            return
        }
        if (!CaptureService.running) {
            toast("Screen capture is off. Open MacroLoop, press Edit Mode, and tap Start.")
            return
        }
        scope.launch {
            setHidden(true)
            CaptureService.instance?.fast = true
            delay(450)
            val frame = CaptureService.grabFresh(SystemClock.elapsedRealtime() - 100, 400)
            CaptureService.instance?.fast = false
            if (frame == null) {
                setHidden(false)
                toast("Could not see the screen. Try again.")
                return@launch
            }
            val scale = if (capW > 0) frame.width.toDouble() / capW else 1.0
            val region = if (whole) null else Matcher.nearRegion(bx, by, bw, bh, scale, frame.width, frame.height)
            val res = withContext(Dispatchers.Default) { Matcher.find(frame, t, region, threshold, scale) }
            DebugState.record(frame, res, threshold, name)
            if (!res.tooPlain) showFlash(res)
            val pct = (res.score * 100).toInt()
            val msg = when {
                res.tooPlain -> "That picture is too plain to find. Use a bigger box with more detail."
                res.found && mustAppear -> "Found, $pct% (${res.ms} ms). The condition holds."
                res.found -> "Found, $pct%. The condition does NOT hold, it should be gone."
                mustAppear -> "Not found. Best match was $pct%, needs $threshold%."
                else -> "Not found, as wanted (best $pct%). The condition holds."
            }
            setHidden(false)
            toast(msg)
            delay(2500)
            removeFlash()
        }
    }

    private fun showFlash(res: Matcher.Result) {
        removeFlash()
        val v = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), if (res.found) TokenInts.success else TokenInts.warning)
            }
        }
        val p = WindowManager.LayoutParams(
            res.w, res.h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = res.x
            y = res.y
        }
        wm.addView(v, p)
        flashViews.add(v)
    }

    private fun removeFlash() {
        flashViews.forEach { rm(it) }
        flashViews.clear()
    }

    /**
     * kind 0 = the whole macro. 1 = only this step. 2 = this step and everything after it in its list.
     * The editor hides itself while the test runs and comes back when it ends.
     */
    private fun runTest(kind: Int, ref: Ref) {
        closeMenu()
        if (mainSteps.isEmpty()) {
            toast("Add a step to the Main Job first.")
            return
        }
        val svc = MacroService.instance ?: return
        val macro = current().copy(repeat = 1)
        val done = { setHidden(false) }
        when (kind) {
            1 -> {
                val s = stepAt(ref) ?: return
                setHidden(true)
                svc.testStep(macro, s, done)
            }
            2 -> {
                if (stepAt(ref) == null) return
                setHidden(true)
                svc.testFrom(macro, listAt(ref.path), ref.index, done)
            }
            else -> {
                setHidden(true)
                svc.runMacro(macro, done)
            }
        }
    }

    // ================================================================ saving

    private fun save() {
        MacroRepository.save(current())
        dirty = false
        // delete picture files nothing uses any more
        val used = HashSet<String>()
        for (m in MacroRepository.macros) for (s in flat(m.lists.flatten())) {
            if (s.templateId.isNotEmpty()) used.add(s.templateId)
            for (c in s.conditions) if (c.templateId.isNotEmpty()) used.add(c.templateId)
            s.holdUntil?.let { if (it.templateId.isNotEmpty()) used.add(it.templateId) }
        }
        TemplateStore.prune(used)
        MacroScheduler.reschedule(ctx)
        refreshPanelIfOpen()
    }

    // ================================================================ hiding while a test runs

    private fun setHidden(h: Boolean) {
        hidden = h
        applyHidden()
    }

    private fun applyHidden() {
        for (v in listOf(chip, panel, menu, overlay, capBox, capPanel)) {
            if (v == null) continue
            v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            val has = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            if (hidden && !has) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(v, lp) }
            } else if (!hidden && has) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }
    }

    // ================================================================ small helpers

    private fun thumb(templateId: String): ImageView? {
        val bmp = TemplateStore.load(templateId) ?: return null
        return ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFE8EAF0.toInt())
        }
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
}
