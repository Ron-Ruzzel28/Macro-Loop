package com.macroloop.app

/**
 * The help texts shown by the small (i) buttons in the editor. Plain words, no jargon.
 * [short] is what the (i) shows first. MORE adds the example, the best tip and a common mistake.
 */
class Topic(val id: String, val group: String, val title: String, val short: String, val example: String = "", val tip: String = "", val mistake: String = "")

object HelpTopics {
    val all = ArrayList<Topic>()
    private var group = ""

    private fun g(name: String) { group = name }
    private fun t(id: String, title: String, short: String, example: String = "", tip: String = "", mistake: String = "") {
        all.add(Topic(id, group, title, short, example, tip, mistake))
    }

    fun byId(id: String): Topic? = all.firstOrNull { it.id == id }

    init {
        g("Start here")
        t("tour", "How a macro works", "A macro is a list of steps. MacroLoop does them one after another, top to bottom. A step can tap, swipe, wait or look at the screen.",
            "Step 1: Open App. Step 2: Click Image on the Claim button. Step 3: Wait 2 seconds.",
            "Build it one step at a time and press Test this step after each one. Do not build a long macro and test it only at the end.",
            "Adding many steps before testing any. When something is wrong you will not know which step it was.")

        g("Actions (the + button)")
        t("click_image", "Click Image", "Looks for a small picture on the screen and taps it. This is the most reliable way to tap a button, because it still works if the button moves.",
            "Capture the Claim button. MacroLoop finds it and taps it, wherever it is on the screen.",
            "Draw the box tight around the part of the button that never changes, then use Re-crop to trim it.",
            "Including the background in the picture. When the background changes, the picture is no longer found.")
        t("click_xy", "Click (x, y)", "Taps one fixed spot on the screen, even if nothing is there.",
            "Tap the empty corner to close a menu.",
            "Use it only for spots that never move. For buttons, Click Image is safer.",
            "Using it for a button that sometimes shifts. The tap lands on the wrong thing.")
        t("swipe", "Swipe (x, y)", "Drags a finger from one spot to another, for scrolling or sliding.",
            "Swipe up to scroll a list, then look for the item you want.",
            "Make it slower with Edit Swipe Speed if the game does not notice a fast swipe.",
            "Swiping too fast. Many games ignore it.")
        t("wait", "Wait", "Pauses the macro for a set time.",
            "Wait 3 seconds after opening a game while it loads.",
            "Better still, use a Click Image or a condition to wait for the exact thing you need. A fixed wait is either too long or too short.",
            "Using long waits to hide a problem. It makes the macro slow and still unreliable.")
        t("open_app", "Open App", "Opens an app by name, without needing to find its icon.",
            "Open the game at the start of the macro.",
            "Put a Wait after it so the app has time to load.",
            "Starting the next step straight away, before the app is ready.")
        t("toast", "Toast Message", "Shows a short message on screen while the macro runs.",
            "Show \"Claimed!\" after a reward is collected.",
            "Handy while testing, to see which part of the macro is running.", "")
        t("press_back", "Press Back", "Presses the phone's Back button.", "Close a popup that has no X button.", "Good for backing out of a menu when you do not know what is open.")
        t("press_home", "Press Home", "Goes to the home screen.", "End of a macro: go home.", "Use it before Open App to start from a clean place.")
        t("open_recent", "Open Recent", "Opens the list of recent apps.", "Switch to another app from the list.", "The list may look different on each phone, so check it with Test this step.")
        t("open_notification", "Open Notification", "Pulls down the notification shade.", "Read a message that appears there.", "Press Back or Home afterwards to close it.")
        t("open_quicksetting", "Open Quicksetting", "Pulls down the quick settings (Wi-Fi, flashlight and so on).", "Turn a setting on or off.", "Check where the buttons are on your phone first.")
        t("lock_screen", "Lock Screen", "Locks the phone.", "The last step of a night macro.", "After this, the macro cannot tap anything. Put it last.", "Putting steps after it. They will not work.")
        t("counter_add", "Add to Counter", "Adds a number to a counter. A counter is a number the macro can remember.",
            "After each reward claimed, add 1 to the counter \"claims\".",
            "Pair it with the condition Counter At Least, to stop after a number of times.")
        t("counter_reset", "Reset Counter", "Sets a counter back to 0.", "Start of each day: reset \"claims\".", "Use it on counters that keep their number between runs.")
        t("stop", "Stop Macro", "Ends the macro right here.", "Stop when the counter reaches 5.", "Put a condition on it, otherwise the macro always stops at this step.", "Forgetting the condition, so the macro stops straight away.")
        t("goto", "Go to Step", "Jumps to another step of the Main Job and goes on from there.", "At the end of the list, go back to step 1 to repeat.",
            "Use it to make a loop. A safety limit stops a loop that never ends.", "A loop with nothing that can stop it.")
        t("link", "Run Step (link)", "Runs another step (with its conditions and branches), then carries on here.", "Run the same \"close popup\" step from several places.",
            "Great for reusing steps. Make the linked step \"Run only when linked\" so it does not also run on its own.", "Deleting a step that a link points to. The link breaks.")
        t("paste", "Paste", "Puts the step you copied here.", "Copy a step, move to another place, Paste.", "Paste makes a new copy. Changing it does not change the original.")

        g("Conditions (what must be true)")
        t("cond_image_appear", "Image Appears", "The step only runs when this picture is on the screen.", "Only tap Collect when the \"Ready\" icon is showing.", "Good for icons and logos.")
        t("cond_image_not", "Image Not Appear", "The step only runs when this picture is NOT on the screen.", "Only start a new battle when the \"Battling\" icon is gone.", "Use it to wait for something to finish.")
        t("cond_color_appear", "Colour Appears", "The step only runs when this colour is on the screen.", "A bar turns green when it is ready.", "Good for bars, badges and red dots. Colour is quicker than a picture. Use a small area.", "Picking a colour that is also in the background.")
        t("cond_color_not", "Colour Not Appear", "The step only runs when this colour is NOT there.", "Wait until the red badge goes away.", "Pick the colour from the screen with the box, not by guessing.")
        t("cond_color_changes", "Colour Changes", "True when the colour in the area changes from how it was.", "Hold a button until the bar changes colour.", "Draw the box around the part that changes and nothing else.", "A box that includes something that always moves. It always counts as changed.")
        t("cond_text_appear", "Text Appears", "The step only runs when these words are on the screen. MacroLoop reads the screen, no internet needed.", "Only continue when \"Daily Reward\" is showing.",
            "Good for labels, because it still works when the colours or the style change. Draw the box around just the words.", "Typing the words differently from the screen. Spelling matters (capital letters do not).")
        t("cond_text_not", "Text Not Appear", "The step only runs when these words are NOT on the screen.", "Wait until \"Loading\" is gone.", "A good way to wait for a loading screen to end.")
        t("cond_action_result", "Action Result", "True if the step before worked (Success) or did not work (Failed).", "If the last tap failed, press Back.", "Use Failed to build a fix for when something goes wrong.")
        t("cond_random", "Random True", "True some percent of the time, by chance.", "50%: sometimes do an extra tap so the macro looks less robotic.", "Use a low percent for rare extras.")
        t("cond_once", "Once True", "True only the first time in one run.", "Show a welcome step only the first time around the loop.", "Good inside a loop, for things that should happen once.")
        t("cond_counter_least", "Counter At Least", "True when the counter is at least this number.", "Stop when \"claims\" is at least 5.", "Use with Add to Counter.")
        t("cond_counter_below", "Counter Below", "True while the counter is smaller than this number.", "Keep claiming while \"claims\" is below 5.", "Use it to limit how many times something happens.")
        t("all_true", "All true / One true", "When a step has several conditions, All true means every one must hold. One true means any one is enough. Tap it to switch.",
            "Image Appears AND Text Appears: both must show. Or choose One true if either is fine.", "Start with All true. It is the safest.")
        t("own_picture", "The step's own picture", "The picture this step looks for and taps. Tap the row to re-crop it, change it, or test it.", "", "Press Test Condition to see if it is found right now.")
        t("hold_row", "Hold until", "Presses and holds the button until the picture or colour shows up (or goes away), then lets go.", "Hold Collect until the bar fills.",
            "Set the longest hold a little more than the real time.", "A longest hold that is too short. It lets go too early.")

        g("Step menu")
        t("recrop", "Re-crop Image", "Opens the saved picture big. Drag the corners to crop it smaller, and drag the dot to choose where the tap lands.", "Trim off the background so only the button is left.",
            "Smaller and plainer is usually better. Press Test Condition after.", "Cropping so small that the picture is plain. It will be refused.")
        t("change_image", "Change Image", "Take a fresh picture from the screen to replace this one.", "The game was updated and the button looks different.", "Use Re-crop to trim the old one. Use Change Image only for a new look.")
        t("offset", "Edit Offset", "Moves the tap a little from the middle of what was found.", "Tap on the right edge of a long bar.", "The dot in Re-crop sets this by dragging, which is easier than numbers.")
        t("click_style", "Edit Click Style", "How the tap is done: how many taps, how long to hold, a bit of random, two fingers.", "Double tap, or a long press of 800 ms.",
            "Leave the random offset on a little so taps are not always on the exact same pixel.")
        t("hold_until", "Hold Until...", "Holds the press until a picture or colour appears, or goes away.", "Hold Collect until the bar is full.", "Use it when the game wants a long press that ends when something happens.")
        t("delay", "Edit Delay", "Time to wait before the step, and after the step.", "Wait 500 ms after the tap so the screen can change.", "A short wait after a tap often fixes a step that runs too soon.")
        t("edit_image", "Edit Image", "How long to look, what to do when it is not found, and the finder's extra options.", "Look for 15 seconds, or skip if it is not found.",
            "Choose Skip only when it is fine to carry on without it.", "Leaving it on stop when the picture is optional. The whole macro stops.")
        t("move_spot", "Move Spot", "Choose a new place for this tap.", "")
        t("redraw_swipe", "Redraw Swipe", "Draw the swipe again.", "")
        t("swipe_speed", "Edit Swipe Speed", "How long the swipe takes. Longer means slower.", "", "If the game does not react, make it slower.")
        t("wait_time", "Edit Wait Time", "How many seconds to wait.", "")
        t("change_target", "Change Where It Goes", "Pick a different step to jump to.", "")
        t("edit_message", "Edit Message", "Change the words of the message.", "")
        t("change_app", "Change App", "Pick a different app to open.", "")
        t("change_link", "Change Link", "Pick a different step to run.", "")
        t("test_step", "Test this step", "Runs only this step, so you can see it work.", "Test the Claim tap without running the whole macro.", "Test every step as you build it.")
        t("test_from", "Test from this step", "Runs this step and everything after it.", "Test the second half of the macro.", "Open the game to the right screen first.")
        t("add_above", "Add Action (Above)", "Adds a new step above this one.", "")
        t("add_condition", "Add Condition", "Makes the step run only when something is true, like a picture, colour, words or a counter.", "Only tap when the \"Ready\" text shows.", "Conditions make a macro safe. Use them on steps that could do damage at the wrong time.")
        t("replace_action", "Replace Action", "Changes what this step does but keeps its name, conditions and branches.", "")
        t("rename", "Rename", "Gives the step your own name so it is easy to find.", "Name a step \"Close popup\".", "Names help a lot when links point at steps.")
        t("if_true", "Add step if true", "Steps that run after this one worked. They run, then the list carries on.", "After the claim worked, add 1 to the counter.", "Use the green \"If true\" to do the next thing only when this one worked.")
        t("if_false", "Add step if false", "Steps that run when this step could not do its job (for example the picture never showed up).", "If the Claim button never shows, press Back.",
            "This is how you make a macro fix itself. Without it, the macro stops when a step fails.", "Leaving it empty on an important step.")
        t("link_true", "Link a step if true", "Instead of copying steps, point to a step that already exists and run it.", "")
        t("link_false", "Link a step if false", "Same as above, for when the step fails.", "")
        t("linked_only", "Run only when linked", "The step is skipped in the normal order and only runs when a link calls it.", "A \"close popup\" step used by links.", "Say yes when asked, so it does not run twice.")
        t("duplicate", "Duplicate", "Makes a copy of the step right under it.", "")
        t("copy", "Copy", "Copies the step. Use Paste in the + menu to put it somewhere.", "")
        t("cut", "Cut", "Removes the step but keeps it ready to Paste.", "")
        t("delete", "Delete", "Removes the step.", "", "", "Deleting a step that links point to. The links break and the macro stops there.")
        t("disable", "Disable / Enable", "Skips the step without deleting it.", "Turn off a step while you test something else.", "Easier than deleting when you are not sure.")
        t("cond_off", "Turn Off (keep it)", "Ignores this condition without deleting it.", "Try the step without the condition, then turn it back on.")
        t("similarity", "Edit Similarity %", "How much the picture on screen must look like the saved one. Higher is stricter. Use the slider.", "", "New pictures start at 70 in their exact spot. Lower it if the picture is missed. Raise it if it taps the wrong thing.", "Setting it very low. It will tap look-alikes.")
        t("auto_tune", "Auto-tune Similarity", "Looks at the screen for about a second and sets the similarity just under the picture's weakest match. A picture that glows or moves gets a lower number automatically. The picture must be on the screen.",
            "The button matches at 97%, so it is set to 91%: found every time, but a look-alike is not.", "Do it on the real screen where the picture really shows.", "Doing it on a different screen. It will say the match is too low.")
        t("pick_colour", "Pick Colour Again", "Draw the box again, then tap the picture to choose the exact colour. Fine-tune with the sliders or the hex box.",
            "Tap the green of a health bar to take exactly that green.", "Tap a spot in the middle of the colour, not on an edge where colours mix.")
        t("detect_location", "Edit Detect Location", "Exact spot looks only at the square you drew (plus a few pixels). Near looks around it. Whole screen looks everywhere.",
            "A button that is always in the same place: Exact spot. A picture that moves around: Whole screen.",
            "Exact spot is the fastest and steadiest, and cannot jump to a look-alike elsewhere.", "Exact spot for a picture that moves. It will not be found.")
        t("change_appear", "Change to Appears / Not appear", "Switch between \"the picture is there\" and \"the picture is gone\".", "")
        t("test_condition", "Test Condition", "Looks at the screen right now and says if the picture is found.", "", "Use it on the real screen before you trust a condition.")
        t("chance", "Edit Chance %", "How often the random condition is true.", "")
        t("longest_hold", "Edit Longest Hold", "The longest the press may be held before it lets go.", "", "A little more than the real time it takes.")
        t("remove_hold", "Remove Hold Until", "Takes the hold-until away from this step.", "")
        t("change_counter", "Change Counter", "Pick a different counter.", "")
        t("amount", "Edit Amount", "How much to add each time. A minus number takes away.", "")
        t("number", "Edit Number", "The number the counter is compared with.", "")

        g("Macro menu (tap the title)")
        t("sec_main", "Main Job", "The normal list of steps. It runs top to bottom.")
        t("sec_watchers", "Watchers", "Things the macro watches for all the time, like a popup. When one shows up, its steps run, then the macro carries on.", "If the \"X\" of an ad shows up, tap it.",
            "Use a watcher for popups, instead of adding extra steps everywhere.", "A watcher with no condition does nothing.")
        t("sec_stuck", "Stuck rescue", "Steps that run when the screen has been frozen for a while (set in Stuck check).", "Press Back, then open the game again.", "With no steps here, the macro just stops with a message.")
        t("jobs", "Jobs", "A job is a named folder of steps, such as Fallback. The Main Job loops. Other jobs sit idle until something calls them, then the macro carries on after the caller.",
            "A job 'Close popups' holds three steps. A Run job step, an If false, or a watcher calls it when needed.",
            "Use jobs for the parts you reuse or the fallbacks, so the Main Job stays short.", "A job that calls itself. The macro stops with a message.")
        t("run_job", "Run Job", "Runs a whole job, then the macro carries on after this step. It can have conditions and If true / If false like any step.",
            "If the Claim picture is not found, run the job Fallback.", "Put it under If false to make a fallback.", "Deleting the job later. The step shows BROKEN and is skipped.")
        t("job_auto", "Run by itself when...", "Makes the job run on its own whenever a picture, colour, words or counter is seen, even when no step calls it.",
            "A job 'Close popup' that runs whenever the popup's X is on screen.", "The top card of the job holds the condition. Use Run only when called to turn it off.", "A condition that is always true. The job then runs again and again.")
        t("loop", "Loop", "How many times the whole list of steps goes round: forever (until you stop it) or a number of times.",
            "Forever, with 'Stop when it sees' a picture: it keeps going round until that picture shows up.",
            "New macros loop forever. Press the red Stop button to end any run.", "Forever with nothing to stop it. You then have to press Stop.")
        t("stop_when", "Stop when it sees...", "Makes a watcher that stops the macro when a picture, colour, words or a counter is seen.",
            "Stop when the 'Quest complete' picture appears.", "Look in Watchers to change or remove it later.", "Choosing something that is always on screen. It stops straight away.")
        t("scan_mode", "Scan mode", "ON: the macro goes through the steps quickly. Each step looks at the screen once. If its picture is there it does the action, if not it skips that step at once, and the list starts again. OFF: a step waits (up to its time limit) for its picture.",
            "Step 1 taps Claim when it shows, step 2 taps Close when it shows. With Scan mode the macro loops and does whichever one is on screen right now.",
            "Keep it on for games. Set Repeat to 0 on the macro's info page to keep going until you press Stop. A pass where nothing happened does not count as a repeat.",
            "Expecting it to wait for a slow loading screen. Use Scan mode off for a step that must wait.")
        t("counters", "Counters", "Numbers the macro can add to and check. You make them here, then use them in steps and conditions.", "\"claims\", kept between runs, to stop after 5 a day.", "Tick \"keep between runs\" for daily limits.")
        t("stuck_check", "Stuck check", "If the screen does not change for this many seconds, MacroLoop runs the Stuck rescue steps.", "30 seconds.",
            "A game with a moving background is not counted as stuck. It looks for a frozen screen. Needs screen capture.", "Setting it shorter than a normal long wait.")
        t("schedule", "Schedule", "Starts the macro by itself at times you choose, on days you choose.", "06:00 and 18:30, every day, stop after 20 minutes.",
            "Before it starts the phone must be unlocked, Accessibility on and screen capture started. Press Save to apply.", "Expecting it to run on a locked phone.")
        t("help_topics", "Help topics", "Lists every help tip so you can read them in one place.")

        g("Round button and menus")
        t("manage", "Manage Actions", "Opens the list of steps.")
        t("run_test", "Run Test", "Runs the whole macro once, from the top.", "", "Open the game to the starting screen first.")
        t("save", "Save", "Saves the macro. Do this often.", "", "Schedules only start working after you Save.")
        t("more", "More", "Extra settings: timing and debug.")
        t("exit", "Exit", "Closes the editor.", "", "Choose Save and exit, or you can lose your changes.")
        t("random_setting", "Random Setting", "Adds small random pauses and tap offsets so taps are never exactly the same.", "", "Leave it on.")
        t("debug_setting", "Debug Setting", "Show Detect Location draws a box where a picture was found. Show taps draws a ring where each tap is sent, so you can see if MacroLoop tapped and where.", "", "Turn them on while you test, off afterwards. If a step 'does not tap', Show taps tells you if the tap was sent.")
        t("btn_shrink", "Shrink button", "Folds the step list back into the round button.")
        t("btn_test", "Test button (green play)", "Runs the whole macro once, from the top.")
        t("btn_plus", "Plus button", "Adds a step. In Watchers it adds a new watcher.")
        t("btn_redo", "Reset box", "Puts the capture box back where it started.")
        t("btn_cancel", "Cancel capture", "Closes the capture box without saving.")
        t("btn_ok", "Confirm capture", "Saves what is inside the box as the picture (or colour, or area).", "", "Make sure the box covers only the part you want.")
    }

    private val exact = mapOf(
        "click (x, y)" to "click_xy", "click image" to "click_image", "swipe (x, y)" to "swipe", "wait" to "wait", "open app" to "open_app",
        "toast message" to "toast", "press back" to "press_back", "press home" to "press_home", "open recent" to "open_recent",
        "open notification" to "open_notification", "open quicksetting" to "open_quicksetting", "lock screen" to "lock_screen",
        "add to counter" to "counter_add", "reset counter" to "counter_reset", "stop macro" to "stop", "go to step" to "goto",
        "run step (link)" to "link", "run job" to "run_job", "paste" to "paste",
        "image appears" to "cond_image_appear", "image not appear" to "cond_image_not", "colour appears" to "cond_color_appear",
        "colour not appear" to "cond_color_not", "colour changes" to "cond_color_changes", "text appears" to "cond_text_appear",
        "text not appear" to "cond_text_not", "action result" to "cond_action_result", "random true" to "cond_random", "once true" to "cond_once",
        "counter at least" to "cond_counter_least", "counter below" to "cond_counter_below",
        "re-crop image" to "recrop", "change image" to "change_image", "edit offset" to "offset", "edit click style" to "click_style",
        "hold until..." to "hold_until", "edit hold until" to "hold_until", "edit delay" to "delay", "edit image" to "edit_image",
        "move spot" to "move_spot", "redraw swipe" to "redraw_swipe", "edit swipe speed" to "swipe_speed", "edit wait time" to "wait_time",
        "change where it goes" to "change_target", "edit message" to "edit_message", "change app" to "change_app", "change link" to "change_link",
        "test this step" to "test_step", "test from this step" to "test_from", "add action (above)" to "add_above", "add condition" to "add_condition",
        "replace action" to "replace_action", "rename" to "rename", "add step if true" to "if_true", "add step if false" to "if_false",
        "link a step if true" to "link_true", "link a step if false" to "link_false", "run only when linked" to "linked_only",
        "run in order again" to "linked_only", "duplicate" to "duplicate", "copy" to "copy", "cut" to "cut", "delete" to "delete",
        "disable" to "disable", "enable" to "disable", "turn off (keep it)" to "cond_off", "turn on" to "cond_off",
        "edit similarity %" to "similarity", "auto-tune similarity" to "auto_tune", "pick colour again" to "pick_colour", "edit detect location" to "detect_location", "test condition" to "test_condition",
        "edit chance %" to "chance", "edit longest hold" to "longest_hold", "remove hold until" to "remove_hold",
        "change counter" to "change_counter", "edit amount" to "amount", "edit number" to "number",
        "main job" to "sec_main", "manage actions" to "manage", "run test" to "run_test", "save" to "save", "more" to "more", "exit" to "exit",
        "random setting" to "random_setting", "debug setting" to "debug_setting",
    )

    private val prefixes = listOf(
        "change to appears" to "change_appear", "change to not appear" to "change_appear", "change to:" to "change_appear",
        "change to success" to "cond_action_result", "change to failed" to "cond_action_result",
        "watchers" to "sec_watchers", "stuck rescue" to "sec_stuck", "counters" to "counters", "stuck check" to "stuck_check",
        "schedule" to "schedule", "help topics" to "help_topics", "scan mode" to "scan_mode", "loop:" to "loop", "stop when" to "stop_when",
        "job:" to "jobs", "new job" to "jobs", "rename this job" to "jobs", "delete this job" to "jobs", "run by itself" to "job_auto", "run only when called" to "job_auto",
        "change job" to "run_job", "open job" to "jobs",
    )

    /** The help for a menu row or a button, found by its words. Null when there is nothing to say. */
    fun find(text: String): Topic? {
        val k = text.trim().lowercase().removePrefix("✓").trim()
        exact[k]?.let { return byId(it) }
        prefixes.firstOrNull { k.startsWith(it.first) }?.let { return byId(it.second) }
        return null
    }

    fun forStep(type: StepType): Topic? = byId(when (type) {
        StepType.CLICK -> "click_xy"; StepType.SWIPE -> "swipe"; StepType.WAIT -> "wait"; StepType.CLICK_IMAGE -> "click_image"
        StepType.STOP_MACRO -> "stop"; StepType.GOTO -> "goto"; StepType.LINK -> "link"; StepType.PRESS_BACK -> "press_back"
        StepType.PRESS_HOME -> "press_home"; StepType.OPEN_RECENT -> "open_recent"; StepType.OPEN_NOTIFICATION -> "open_notification"
        StepType.OPEN_QUICKSETTING -> "open_quicksetting"; StepType.LOCK_SCREEN -> "lock_screen"; StepType.TOAST -> "toast"
        StepType.OPEN_APP -> "open_app"; StepType.COUNTER_ADD -> "counter_add"; StepType.COUNTER_RESET -> "counter_reset"
        StepType.WATCH -> "sec_watchers"
        StepType.RUN_JOB -> "run_job"
    })

    fun forCondition(c: Condition): Topic? = byId(when (c.kind) {
        ConditionKind.IMAGE -> if (c.mustAppear) "cond_image_appear" else "cond_image_not"
        ConditionKind.COLOR -> if (c.changes) "cond_color_changes" else if (c.mustAppear) "cond_color_appear" else "cond_color_not"
        ConditionKind.TEXT -> if (c.mustAppear) "cond_text_appear" else "cond_text_not"
        ConditionKind.ACTION_RESULT -> "cond_action_result"
        ConditionKind.RANDOM -> "cond_random"
        ConditionKind.ONCE -> "cond_once"
        ConditionKind.COUNTER -> if (c.mustAppear) "cond_counter_least" else "cond_counter_below"
    })

    /** A check that every topic id in the lookups exists, for the self-test. */
    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        val missing = (exact.values + prefixes.map { it.second }).filter { byId(it) == null }.distinct()
        if (missing.isNotEmpty()) out += "FAIL  help: unknown topics $missing"
        val dup = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) out += "FAIL  help: duplicate ids $dup"
        for (t in StepType.entries) if (forStep(t) == null) out += "FAIL  help: no topic for step $t"
        for (k in ConditionKind.entries) if (forCondition(Condition(kind = k)) == null) out += "FAIL  help: no topic for condition $k"
        if (find("Re-crop Image")?.id != "recrop") out += "FAIL  help: row lookup"
        out.add(0, "Help topics: ${all.size} written, ${exact.size + prefixes.size} labels linked")
        return out
    }
}
