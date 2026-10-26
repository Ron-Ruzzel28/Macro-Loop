package com.macroloop.app

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Screen { HOME, SETUP, DEBUG, INFO }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASK_CAPTURE = "ask_capture"
        const val EXTRA_SELF_TEST = "self_test"
    }

    private var accessibilityOn by mutableStateOf(false)
    private var batteryOk by mutableStateOf(false)
    private var returnAfterCapture = false
    private var pendingId: String? = null
    private var pendingMode = 0            // 1 = Edit Mode, 2 = Play Mode

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data),
            )
            val id = pendingId
            if (id != null) openMode(pendingMode, id, 700)
            else if (returnAfterCapture) moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_LONG).show()
        }
        pendingId = null
        pendingMode = 0
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

    private fun runSelfTest() {
        val dir = filesDir
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { SelfTest.run(dir) }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_SELF_TEST, false) == true) {
            i.removeExtra(EXTRA_SELF_TEST)
            runSelfTest()
        }
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
        License.init(applicationContext)
        setContent { MacroLoopTheme { App() } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // The first check, which starts the trial, waits until the person has been asked for their name.
        if (License.hasAskedName(this)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { License.checkIn(applicationContext) }
        }
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
        var infoId by remember { mutableStateOf("") }
        when (screen) {
            Screen.HOME -> HomeScreen(
                onSetup = { screen = Screen.SETUP },
                onDebug = { screen = Screen.DEBUG },
                onInfo = { infoId = it; screen = Screen.INFO },
            )
            Screen.INFO -> InfoScreen(infoId) { screen = Screen.HOME }
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
    private fun TopBar(title: String, leading: String?, onLeading: () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
        Row(
            Modifier.fillMaxWidth().background(Tokens.primary).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Text(leading, fontSize = 24.sp, color = Tokens.onPrimary, modifier = Modifier.clickable { onLeading() }.padding(12.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(title, fontSize = 22.sp, color = Tokens.onPrimary, modifier = Modifier.weight(1f))
            trailing()
        }
    }

    @Composable
    private fun HomeScreen(onSetup: () -> Unit, onDebug: () -> Unit, onInfo: (String) -> Unit) {
        var showNew by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        val homeCtx = LocalContext.current
        var askName by remember { mutableStateOf(!License.hasAskedName(homeCtx)) }

        Column(Modifier.fillMaxSize()) {
            TopBar("MacroLoop", null) {
                Text("⚡", fontSize = 22.sp, color = Tokens.onPrimary, modifier = Modifier.clickable { onSetup() }.padding(12.dp))
                Box {
                    Text("⋮", fontSize = 24.sp, color = Tokens.onPrimary, modifier = Modifier.clickable { menuOpen = true }.padding(12.dp))
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Set up and permissions") }, onClick = { menuOpen = false; onSetup() })
                        DropdownMenuItem(text = { Text("Picture test (advanced)") }, onClick = { menuOpen = false; onDebug() })
                    }
                }
            }
            LicenseBanner()
            if (!accessibilityOn) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("1 thing needs setup", fontSize = 18.sp, color = Tokens.warning, fontWeight = FontWeight.SemiBold)
                        Text("MacroLoop needs permission to tap for you.", fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Set up now", onClick = onSetup)
                    }
                }
            }
            if (MacroRepository.macros.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Nothing here yet", fontSize = 20.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tap + to make your first macro. Then press EDIT MODE to build it over your game or app.",
                        fontSize = 15.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 96.dp),
                ) {
                    items(MacroRepository.macros, key = { it.id }) { m -> MacroRow(m) { onInfo(m.id) } }
                }
            }
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Tokens.primary).clickable { showNew = true },
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 32.sp, color = Tokens.onPrimary) }
        }

        if (askName) {
            var nm by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { },
                containerColor = Tokens.surfaceHigh,
                title = { Text("Who is this phone for?", color = Tokens.text) },
                text = {
                    Column {
                        Text(
                            "Your name helps the owner know which phone to switch on. MacroLoop sends a short code for this phone and your name to the owner's server. Nothing else leaves your phone.",
                            color = Tokens.textMuted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = nm, onValueChange = { nm = it }, singleLine = true, label = { Text("Your name") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        License.setName(homeCtx, nm)
                        askName = false
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { License.checkIn(homeCtx.applicationContext) }
                    }) { Text("Continue", color = Tokens.primary, fontSize = 16.sp) }
                },
            )
        }

        if (showNew) NewMacroDialog(
            onCreate = { name ->
                val m = MacroRepository.create(name)
                showNew = false
                startMode(m, 1)
            },
            onCancel = { showNew = false },
        )
    }

    /** Trial time left, or the lock card with this phone's code for the owner. */
    @Composable
    private fun LicenseBanner() {
        val ctx = LocalContext.current
        val st = License.state
        if (License.usable) {
            if (st == License.State.TRIAL) {
                Text(
                    "Trial: ${License.leftText(ctx)} left",
                    fontSize = 13.sp, color = Tokens.warning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            return
        }
        val code = remember { License.deviceCode(ctx) }
        val waiting = st == License.State.UNKNOWN && !License.offline
        Card(
            colors = CardDefaults.cardColors(containerColor = Tokens.surface),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    when {
                        st == License.State.REVOKED -> "This phone is switched off"
                        waiting -> "Checking your access..."
                        st == License.State.UNKNOWN -> "MacroLoop needs the internet"
                        else -> "Access has ended"
                    },
                    fontSize = 18.sp, color = if (waiting) Tokens.text else Tokens.danger, fontWeight = FontWeight.SemiBold,
                )
                if (License.message.isNotBlank()) Text(License.message, fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))
                if (!waiting) {
                    Text("Send this code to the owner to switch this phone on:", fontSize = 13.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 10.dp))
                    Text(code, fontSize = 24.sp, color = Tokens.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.padding(top = 6.dp)) {
                        PrimaryButton("Copy code", Modifier.weight(1f)) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("MacroLoop code", code))
                            Toast.makeText(ctx, "Code copied", Toast.LENGTH_SHORT).show()
                        }
                        Spacer(Modifier.width(8.dp))
                        SecondaryButton(if (License.checking) "Checking..." else "Check again", Modifier.weight(1f)) {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { License.checkIn(ctx.applicationContext) }
                        }
                    }
                }
            }
        }
    }

    private fun firstThumb(m: Macro): ImageBitmap? {
        fun find(list: List<Step>): String? {
            for (s in list) {
                if (s.type == StepType.CLICK_IMAGE && s.templateId.isNotEmpty()) return s.templateId
                find(s.onSuccess)?.let { return it }
                find(s.onFail)?.let { return it }
            }
            return null
        }
        val id = find(m.steps) ?: return null
        return TemplateStore.load(id)?.asImageBitmap()
    }

    @Composable
    private fun MacroRow(m: Macro, onInfo: () -> Unit) {
        val thumb = remember(m.updatedAt, m.steps.size) { firstThumb(m) }
        Card(
            colors = CardDefaults.cardColors(containerColor = Tokens.surface),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable { onInfo() },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Tokens.surfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(bitmap = thumb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        } else {
                            Text("M", fontSize = 26.sp, color = Tokens.primary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(m.name, fontSize = 20.sp, color = Tokens.text)
                        if (m.game.isNotBlank()) Text(m.game, fontSize = 14.sp, color = Tokens.textMuted)
                        Text(
                            m.version + "  ·  " + (if (m.steps.isEmpty()) "no steps" else m.summary()),
                            fontSize = 13.sp, color = Tokens.textMuted,
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { startMode(m, 1) }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("EDIT MODE", color = Tokens.primary, fontSize = 15.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.width(18.dp))
                    TextButton(onClick = { startMode(m, 2) }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("PLAY MODE", color = Tokens.primary, fontSize = 15.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }

    /** The macro's info page: name, game, version, repeat, delete. Tapping a card opens it. */
    @Composable
    private fun InfoScreen(id: String, onBack: () -> Unit) {
        val m = MacroRepository.get(id)
        if (m == null) {
            LaunchedEffect(id) { onBack() }
            return
        }
        var name by remember(id) { mutableStateOf(m.name) }
        var game by remember(id) { mutableStateOf(m.game) }
        var version by remember(id) { mutableStateOf(m.version) }
        var repeat by remember(id) { mutableStateOf(m.repeat.toString()) }
        var confirmDelete by remember { mutableStateOf(false) }
        fun save() {
            MacroRepository.get(id)?.let {
                MacroRepository.save(
                    it.copy(
                        name = name.trim().ifEmpty { "New macro" },
                        game = game.trim(),
                        version = version.trim().ifEmpty { "1.0.0" },
                        repeat = repeat.toIntOrNull()?.coerceIn(1, 9999) ?: 1,
                    ),
                )
            }
        }
        BackHandler { save(); onBack() }
        Column(Modifier.fillMaxSize()) {
            TopBar("Edit Macro", "←", { save(); onBack() }) {
                Text("SAVE", fontSize = 15.sp, color = Tokens.onPrimary, modifier = Modifier.clickable { save(); onBack() }.padding(12.dp))
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = game, onValueChange = { game = it }, label = { Text("Game or app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = repeat, onValueChange = { repeat = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Repeat the whole macro") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(m.summary(), fontSize = 14.sp, color = Tokens.textMuted)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete this macro", color = Tokens.danger, fontSize = 16.sp)
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = Tokens.surfaceHigh,
                title = { Text("Delete \"${m.name}\"?", color = Tokens.text) },
                text = { Text("This removes the macro and its steps.", color = Tokens.textMuted) },
                confirmButton = {
                    TextButton(onClick = { MacroRepository.delete(id); confirmDelete = false; onBack() }) {
                        Text("Delete", color = Tokens.danger, fontSize = 16.sp)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = Tokens.text, fontSize = 16.sp) } },
            )
        }
    }

    @Composable
    private fun DebugScreen(onBack: () -> Unit) {
        var threshold by remember { mutableStateOf(DebugState.thresholdPct.toFloat()) }
        val preview = DebugState.preview
        val res = DebugState.result
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
                        "\"${DebugState.label}\": best match $score% in ${res.ms} ms" +
                            (if (DebugState.extra.isNotEmpty()) ", plus ${DebugState.extra.size} more" else ""),
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
            Spacer(Modifier.height(12.dp))
            Text("Check the finder", fontSize = 18.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold)
            Text(
                "Runs 20 checks on a drawn screen: brighter, darker, noisy, bigger, smaller, missing and repeated pictures. Also measures speed.",
                fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(vertical = 4.dp),
            )
            PrimaryButton(if (SelfTest.running) "Running..." else "Run the self-test", Modifier.fillMaxWidth()) {
                if (!SelfTest.running) runSelfTest()
            }
            if (SelfTest.report.isNotEmpty()) {
                Text(SelfTest.report, fontSize = 12.sp, color = Tokens.text, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Back to my macros", Modifier.fillMaxWidth()) { onBack() }
            Spacer(Modifier.height(8.dp))
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

    /**
     * Edit Mode (1) and Play Mode (2), like Macrorify: ask for screen capture first (when it is off),
     * then step aside and leave the round button over the phone.
     */
    private fun startMode(m: Macro, mode: Int) {
        if (MacroService.instance == null) {
            Toast.makeText(this, "Turn on MacroLoop in accessibility settings first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!License.allowed(this)) {
            Toast.makeText(this, License.message.ifBlank { "MacroLoop is locked." }, Toast.LENGTH_LONG).show()
            return
        }
        if (mode == 2 && m.steps.isEmpty()) {
            Toast.makeText(this, "This macro has no steps yet. Press EDIT MODE and add some.", Toast.LENGTH_LONG).show()
            return
        }
        if (!CaptureService.running) {
            pendingId = m.id
            pendingMode = mode
            askForCapture()
            return
        }
        openMode(mode, m.id, 0)
    }

    /** Opens the round button for a mode. [waitMs] gives a just-started capture service a moment. */
    private fun openMode(mode: Int, id: String, waitMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            val svc = MacroService.instance
            if (svc == null) {
                Toast.makeText(this, "Turn on MacroLoop in accessibility settings first.", Toast.LENGTH_LONG).show()
            } else {
                if (mode == 1) svc.startEditor(id) else svc.showPlayChip(id)
                moveTaskToBack(true)
            }
        }, waitMs)
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
