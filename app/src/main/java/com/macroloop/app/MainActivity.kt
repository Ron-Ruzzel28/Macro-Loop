package com.macroloop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Screen { HOME, SETUP, DEBUG }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASK_CAPTURE = "ask_capture"
    }

    private var accessibilityOn by mutableStateOf(false)
    private var batteryOk by mutableStateOf(false)
    private var returnAfterCapture = false

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data),
            )
            if (returnAfterCapture) moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_LONG).show()
        }
        returnAfterCapture = false
    }

    private fun askForCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14 and later: offer only "entire screen", with no single-app choice.
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            mgr.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_ASK_CAPTURE, false) == true) {
            i.removeExtra(EXTRA_ASK_CAPTURE)
            returnAfterCapture = true
            askForCapture()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MacroRepository.init(applicationContext)
        TemplateStore.init(applicationContext)
        setContent { MacroLoopTheme { App() } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        accessibilityOn = MacroService.instance != null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf(Screen.HOME) }
        when (screen) {
            Screen.HOME -> HomeScreen(onSetup = { screen = Screen.SETUP }, onDebug = { screen = Screen.DEBUG })
            Screen.DEBUG -> {
                BackHandler { screen = Screen.HOME }
                DebugScreen(onBack = { screen = Screen.HOME })
            }
            Screen.SETUP -> {
                BackHandler { screen = Screen.HOME }
                SetupScreen(onBack = { screen = Screen.HOME })
            }
        }
    }

    @Composable
    private fun HomeScreen(onSetup: () -> Unit, onDebug: () -> Unit) {
        val ctx = LocalContext.current
        var showNew by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<Macro?>(null) }
        var human by remember {
            mutableStateOf(
                ctx.getSharedPreferences(MacroService.PREFS, MODE_PRIVATE)
                    .getBoolean(MacroService.PREF_HUMAN, true),
            )
        }
        val needsSetup = !accessibilityOn

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("My macros", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Tokens.text)
            Spacer(Modifier.height(12.dp))

            if (needsSetup) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("1 thing needs setup", fontSize = 20.sp, color = Tokens.warning, fontWeight = FontWeight.SemiBold)
                        Text(
                            "MacroLoop needs permission to tap for you.",
                            fontSize = 16.sp,
                            color = Tokens.textMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Set up now", onClick = onSetup)
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                val captureText = if (CaptureService.running) "Screen capture: on" else "Screen capture: off (needed for pictures)"
                TextButton(onClick = onSetup, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Set up and permissions  ·  $captureText", color = Tokens.primary, fontSize = 14.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Human-like timing", fontSize = 16.sp, color = Tokens.text)
                    Text("Small random delays and tap offsets", fontSize = 14.sp, color = Tokens.textMuted)
                }
                Switch(
                    checked = human,
                    onCheckedChange = {
                        human = it
                        ctx.getSharedPreferences(MacroService.PREFS, MODE_PRIVATE)
                            .edit().putBoolean(MacroService.PREF_HUMAN, it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Tokens.onPrimary,
                        checkedTrackColor = Tokens.primary,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))

            if (MacroRepository.macros.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    Text("Nothing here yet", fontSize = 20.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tap \"New macro\" to make your first one. You will record it by tapping the spots you want it to tap.",
                        fontSize = 16.sp,
                        color = Tokens.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(MacroRepository.macros, key = { it.id }) { m ->
                        MacroCard(m, onDelete = { deleting = m })
                    }
                }
            }
        }

        // Advanced link, bottom left
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start,
        ) {
            TextButton(onClick = onDebug, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Advanced: picture test", color = Tokens.textMuted, fontSize = 14.sp)
            }
        }

        // New macro button, bottom right
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                containerColor = Tokens.primary,
                contentColor = Tokens.onPrimary,
            ) { Text("New macro", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }

        if (showNew) NewMacroDialog(
            onCreate = { name ->
                val m = MacroRepository.create(name)
                showNew = false
                startEditor(m)
            },
            onCancel = { showNew = false },
        )
        deleting?.let { m ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = Tokens.surfaceHigh,
                title = { Text("Delete \"${m.name}\"?", color = Tokens.text) },
                text = { Text("This removes the macro and its steps.", color = Tokens.textMuted) },
                confirmButton = {
                    TextButton(onClick = { MacroRepository.delete(m.id); deleting = null }) {
                        Text("Delete", color = Tokens.danger, fontSize = 16.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text("Keep it", color = Tokens.text, fontSize = 16.sp) }
                },
            )
        }
    }


    @Composable
    private fun DebugScreen(onBack: () -> Unit) {
        var threshold by remember { mutableStateOf(DebugState.thresholdPct.toFloat()) }
        val preview = DebugState.preview
        val res = DebugState.result
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Picture test", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Tokens.text)
            Text(
                "Shows what the picture finder saw the last time it looked.",
                fontSize = 16.sp,
                color = Tokens.textMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (preview == null) {
                Text(
                    "Nothing to show yet. Tap \"Test now\" in the editor, or run a macro with a picture step.",
                    fontSize = 16.sp,
                    color = Tokens.text,
                )
            } else {
                val fw = DebugState.frameWidth.toFloat()
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Last screen the finder looked at",
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .aspectRatio(preview.width.toFloat() / preview.height)
                        .drawWithContent {
                            drawContent()
                            if (res != null && res.w > 0) {
                                val k = size.width / fw
                                drawRect(
                                    color = if (res.score >= threshold / 100.0) Tokens.success else Tokens.warning,
                                    topLeft = androidx.compose.ui.geometry.Offset(res.x * k, res.y * k),
                                    size = androidx.compose.ui.geometry.Size(res.w * k, res.h * k),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                                )
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                if (res != null) {
                    val score = (res.score * 100).toInt()
                    val pass = res.score >= threshold / 100.0
                    Text(
                        "\"${DebugState.label}\": best match $score%",
                        fontSize = 18.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (res.tooPlain) "This picture is too plain to find."
                        else "At ${threshold.toInt()}% exact it would be: ${if (pass) "Found" else "Not found"}",
                        fontSize = 16.sp,
                        color = if (res.tooPlain) Tokens.warning else if (pass) Tokens.success else Tokens.warning,
                    )
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = 70f..99f,
                        colors = SliderDefaults.colors(thumbColor = Tokens.primary, activeTrackColor = Tokens.primary),
                    )
                    Text("Try different values to pick a good \"How exact?\" setting.", fontSize = 14.sp, color = Tokens.textMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Back to my macros", Modifier.fillMaxWidth()) { onBack() }
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun MacroCard(m: Macro, onDelete: () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Tokens.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(m.name, fontSize = 20.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold)
                Text(m.summary(), fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))

                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Repeat", fontSize = 16.sp, color = Tokens.text, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { MacroRepository.save(m.copy(repeat = (m.repeat - 1).coerceAtLeast(1))) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("-", fontSize = 20.sp, color = Tokens.primary) }
                    Text("${m.repeat}", fontSize = 18.sp, color = Tokens.text)
                    TextButton(
                        onClick = { MacroRepository.save(m.copy(repeat = (m.repeat + 1).coerceAtMost(9999))) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("+", fontSize = 20.sp, color = Tokens.primary) }
                }

                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Edit", Modifier.weight(1f)) { startEditor(m) }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("Run", Modifier.weight(1f)) { startRun(m) }
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Delete", color = Tokens.danger, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun NewMacroDialog(onCreate: (String) -> Unit, onCancel: () -> Unit) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onCancel,
            containerColor = Tokens.surfaceHigh,
            title = { Text("Name your macro", color = Tokens.text) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("For example: Daily claim", color = Tokens.textMuted) },
                )
            },
            confirmButton = {
                TextButton(onClick = { onCreate(name) }) { Text("Create", color = Tokens.primary, fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Cancel", color = Tokens.text, fontSize = 16.sp) }
            },
        )
    }

    @Composable
    private fun SetupScreen(onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Set up", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Tokens.text)
            Text(
                "Turn these on once. This page checks them again each time you come back.",
                fontSize = 16.sp,
                color = Tokens.textMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            SetupRow(
                title = "Let MacroLoop tap for you",
                why = "Turns on the accessibility service. If the switch is grey, open App info, tap the three dots, and choose \"Allow restricted settings\" first.",
                ok = accessibilityOn,
                action = "Open accessibility settings",
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Spacer(Modifier.height(12.dp))
            SetupRow(
                title = "Don't let the battery saver stop it",
                why = "Your phone may close MacroLoop while a macro runs. Allow it to run without limits.",
                ok = batteryOk,
                action = "Allow",
            ) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
            Spacer(Modifier.height(12.dp))
            SetupRow(
                title = "See the screen (for finding pictures)",
                why = "Lets MacroLoop look at the screen to find pictures. Android asks each time MacroLoop is closed or the phone restarts. Nothing is saved or sent.",
                ok = CaptureService.running,
                action = "Allow screen capture",
            ) { askForCapture() }
            if (CaptureService.running) {
                TextButton(onClick = { CaptureService.stop(this@MainActivity) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Turn screen capture off", color = Tokens.danger, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Back to my macros", Modifier.fillMaxWidth()) { onBack() }
        }
    }

    @Composable
    private fun SetupRow(
        title: String,
        why: String,
        ok: Boolean,
        action: String?,
        muted: Boolean = false,
        onClick: () -> Unit,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Tokens.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text)
                Text(why, fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))
                val (word, color) = when {
                    muted -> "Later" to Tokens.textMuted
                    ok -> "Working" to Tokens.success
                    else -> "Needs setup" to Tokens.warning
                }
                Text(
                    (if (ok && !muted) "✓ " else if (muted) "" else "! ") + word,
                    fontSize = 16.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (action != null && !ok) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(action, Modifier.fillMaxWidth(), onClick)
                }
            }
        }
    }

    @Composable
    private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.primary, contentColor = Tokens.onPrimary),
        ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    private fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.surfaceHigh, contentColor = Tokens.text),
        ) { Text(label, fontSize = 16.sp) }
    }

    // ------------------------------------------------------------------ actions

    private fun startEditor(m: Macro) {
        val svc = MacroService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on MacroLoop in accessibility settings first.", Toast.LENGTH_LONG).show()
            return
        }
        svc.startEditor(m.id)
        moveTaskToBack(true)
    }

    private fun startRun(m: Macro) {
        val svc = MacroService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on MacroLoop in accessibility settings first.", Toast.LENGTH_LONG).show()
            return
        }
        if (m.steps.isEmpty()) {
            Toast.makeText(this, "Add at least one step first. Tap Edit.", Toast.LENGTH_LONG).show()
            return
        }
        svc.startRun(m.id)
        moveTaskToBack(true)
    }
}

@Composable
private fun MacroLoopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Tokens.background,
            surface = Tokens.surface,
            primary = Tokens.primary,
            onPrimary = Tokens.onPrimary,
            onBackground = Tokens.text,
            onSurface = Tokens.text,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(Tokens.background),
        ) {
            content()
        }
    }
}
