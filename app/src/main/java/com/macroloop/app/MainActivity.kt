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

private enum class Screen { HOME, SETUP, DEBUG, INFO, ACCOUNT, RUNLOG }

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

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** The other apps that have an accessibility service switched on. Two automation apps at once can fight over taps. */
    private fun otherAccessibilityApps(): List<String> {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return emptyList()
        return raw.split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it)?.packageName }
            .filter { it != packageName }.distinct()
            .map { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
    }

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    /** Reads a .mlm file, asks the server for its key (switched-on phones only) and adds the macro. */
    private fun importFrom(uri: Uri) {
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 40L * 1024 * 1024) return@runCatching null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) {
                Toast.makeText(app, "Could not read that file.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(app, "Opening the macro...", Toast.LENGTH_SHORT).show()
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MacroShare.import(app, bytes) }
            Toast.makeText(app, r.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMacro(m: Macro, runOnly: Boolean) {
        val app = applicationContext
        Toast.makeText(app, "Preparing the file...", Toast.LENGTH_SHORT).show()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MacroShare.export(app, m, runOnly) }
            val f = r.file
            if (!r.ok || f == null) {
                Toast.makeText(app, r.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "MacroLoop macro: ${m.name}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Send macro").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
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
        if (i?.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            i.data = null
            importFrom(u)
        }
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
        RunLog.init(applicationContext)
        DraftStore.init(applicationContext)
        // Android 13 and later: without this, MacroLoop's notices (capture stopped, a scheduled start skipped) are hidden.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val p = getSharedPreferences(MacroService.PREFS, MODE_PRIVATE)
            if (!p.getBoolean("asked_notif", false)) {
                p.edit().putBoolean("asked_notif", true).apply()
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent { MacroLoopTheme { App() } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        MacroScheduler.reschedule(applicationContext)
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
                onAccount = { screen = Screen.ACCOUNT },
                onLog = { screen = Screen.RUNLOG },
            )
            Screen.INFO -> InfoScreen(infoId) { screen = Screen.HOME }
            Screen.ACCOUNT -> {
                BackHandler { screen = Screen.HOME }
                AccountScreen(onBack = { screen = Screen.HOME })
            }
            Screen.RUNLOG -> {
                BackHandler { screen = Screen.HOME }
                RunLogScreen(onBack = { screen = Screen.HOME })
            }
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
    private fun HomeScreen(onSetup: () -> Unit, onDebug: () -> Unit, onInfo: (String) -> Unit, onAccount: () -> Unit, onLog: () -> Unit) {
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
                        DropdownMenuItem(text = { Text("My access") }, onClick = { menuOpen = false; onAccount() })
                        DropdownMenuItem(text = { Text("Run history") }, onClick = { menuOpen = false; onLog() })
                        DropdownMenuItem(text = { Text("Import a macro file") }, onClick = { menuOpen = false; importPicker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Set up and permissions") }, onClick = { menuOpen = false; onSetup() })
                        DropdownMenuItem(text = { Text("Picture test (advanced)") }, onClick = { menuOpen = false; onDebug() })
                    }
                }
            }
            LicenseBanner(onAccount)
            if (accessibilityOn && MacroRepository.macros.any { macroNeedsCapture(it) } && !CaptureService.running) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Screen capture is off", fontSize = 18.sp, color = Tokens.warning, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Your macros look at the screen, so they need it. Android turns it off when MacroLoop is closed.",
                            fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Turn on") { askForCapture() }
                    }
                }
            }
            val lastRun = RunLog.entries.firstOrNull()
            if (lastRun != null && lastRun.problem && System.currentTimeMillis() - lastRun.time < 30 * 60_000L) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { onLog() },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Last run stopped: ${lastRun.macro}", fontSize = 16.sp, color = Tokens.danger, fontWeight = FontWeight.SemiBold)
                        Text(lastRun.result, fontSize = 13.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))
                        Text("Tap to see what happened", fontSize = 12.sp, color = Tokens.primary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
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
            onCreate = { name, landscape ->
                val m = MacroRepository.create(name, landscape)
                showNew = false
                startMode(m, 1)
            },
            onCancel = { showNew = false },
        )
    }

    /** Trial time left, or the lock card with this phone's code for the owner. */
    @Composable
    private fun LicenseBanner(onAccount: () -> Unit) {
        val ctx = LocalContext.current
        val st = License.state
        if (License.usable) {
            // Always visible: the plan, the time left and this phone's code. Tap for the full page.
            val leftMs = License.untilMs - System.currentTimeMillis()
            val soon = License.untilMs != 0L && leftMs < 5L * 24 * 60 * 60 * 1000
            val label = if (st == License.State.TRIAL) "Trial" else "Access"
            Text(
                "$label: ${License.leftText(ctx)} left  ·  ${License.deviceCode(ctx)}",
                fontSize = 13.sp,
                color = if (st == License.State.TRIAL || soon) Tokens.warning else Tokens.textMuted,
                modifier = Modifier.fillMaxWidth().clickable { onAccount() }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                        if (m.landscape) Text("Landscape", fontSize = 13.sp, color = Tokens.textMuted)
                        MacroScheduler.describe(m)?.let { Text("Starts by itself: $it", fontSize = 13.sp, color = Tokens.primary) }
                        if (m.runOnly) Text(
                            "Run only" + if (m.sharedBy.isNotBlank()) " · from ${m.sharedBy}" else "",
                            fontSize = 13.sp, color = Tokens.warning,
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!m.runOnly) {
                        TextButton(onClick = { startMode(m, 1) }, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("EDIT MODE", color = Tokens.primary, fontSize = 15.sp, letterSpacing = 1.sp)
                        }
                        Spacer(Modifier.width(18.dp))
                    }
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
        var askExport by remember { mutableStateOf(false) }
        val infoCtx = LocalContext.current
        fun save() {
            MacroRepository.get(id)?.let {
                MacroRepository.save(
                    it.copy(
                        name = name.trim().ifEmpty { "New macro" },
                        game = game.trim(),
                        version = version.trim().ifEmpty { "1.0.0" },
                        repeat = repeat.toIntOrNull()?.coerceIn(0, 9999) ?: 1,
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
                Spacer(Modifier.height(20.dp))
                if (m.runOnly) {
                    Text(
                        "Run only: shared with you" + (if (m.sharedBy.isNotBlank()) " by ${m.sharedBy}" else "") +
                            ". You can play it but not edit or share it.",
                        fontSize = 14.sp, color = Tokens.warning,
                    )
                } else {
                    PrimaryButton("Export this macro", Modifier.fillMaxWidth()) {
                        if (License.switchedOn(infoCtx)) askExport = true
                        else Toast.makeText(infoCtx, "Only a phone the owner has switched on can share macros.", Toast.LENGTH_LONG).show()
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete this macro", color = Tokens.danger, fontSize = 16.sp)
                }
            }
        }
        if (askExport) {
            AlertDialog(
                onDismissRequest = { askExport = false },
                containerColor = Tokens.surfaceHigh,
                title = { Text("Export \"${m.name}\"", color = Tokens.text) },
                text = {
                    Text(
                        "Run only: they can play it but not see or change the steps.\nFull copy: they can play, edit and export it.\n\nOnly phones the owner has switched on can open the file.",
                        color = Tokens.textMuted, fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { save(); askExport = false; exportMacro(MacroRepository.get(id) ?: m, true) }) {
                            Text("Run only", color = Tokens.primary, fontSize = 16.sp)
                        }
                        TextButton(onClick = { save(); askExport = false; exportMacro(MacroRepository.get(id) ?: m, false) }) {
                            Text("Full copy", color = Tokens.primary, fontSize = 16.sp)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { askExport = false }) { Text("Cancel", color = Tokens.text, fontSize = 16.sp) } },
            )
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

    /** "My access": this phone's code, the plan and time left, and a button to check with the server now. */
    @Composable
    private fun AccountScreen(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val code = remember { License.deviceCode(ctx) }
        var name by remember { mutableStateOf(License.name(ctx)) }
        val st = License.state
        val title = when (st) {
            License.State.TRIAL -> "Free trial"
            License.State.ACTIVE -> "Switched on by the owner"
            License.State.REVOKED -> "Switched off by the owner"
            License.State.LOCKED -> "Access has ended"
            License.State.UNKNOWN -> "Not checked yet"
        }
        Column(Modifier.fillMaxSize()) {
            TopBar("My access", "←", onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (License.usable) Tokens.text else Tokens.danger)
                if (License.usable && License.untilMs != 0L) {
                    Text("${License.leftText(ctx)} left", fontSize = 18.sp, color = Tokens.primary, modifier = Modifier.padding(top = 4.dp))
                    Text("Ends ${License.untilDateText()}", fontSize = 14.sp, color = Tokens.textMuted)
                }
                if (License.message.isNotBlank()) Text(License.message, fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(20.dp))
                Text("This phone's code", fontSize = 13.sp, color = Tokens.textMuted)
                Text(code, fontSize = 28.sp, color = Tokens.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                Text("Send this code to the owner to switch the phone on or renew it.", fontSize = 13.sp, color = Tokens.textMuted)
                Spacer(Modifier.height(10.dp))
                Row {
                    PrimaryButton("Copy code", Modifier.weight(1f)) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("MacroLoop code", code))
                        Toast.makeText(ctx, "Code copied", Toast.LENGTH_SHORT).show()
                    }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(if (License.checking) "Checking..." else "Check now", Modifier.weight(1f)) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { License.checkIn(ctx.applicationContext) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40); License.setName(ctx, name) },
                    singleLine = true, label = { Text("Name shown to the owner") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Last checked: ${License.lastCheckedText(ctx)}", fontSize = 13.sp, color = Tokens.textMuted)
                if (License.offline) Text("No internet right now. MacroLoop keeps working for up to 24 hours after the last check.", fontSize = 13.sp, color = Tokens.warning, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Sharing macros: only a phone the owner has switched on can export or open a shared macro. Trial phones can play their own macros but cannot share.",
                    fontSize = 13.sp, color = Tokens.textMuted,
                )
            }
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
    private fun NewMacroDialog(onCreate: (String, Boolean) -> Unit, onCancel: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var landscape by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onCancel,
            containerColor = Tokens.surfaceHigh,
            title = { Text("Name your macro", color = Tokens.text) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("For example: Daily claim", color = Tokens.textMuted) },
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("How will the phone be held when it runs?", color = Tokens.textMuted, fontSize = 13.sp)
                    Row(Modifier.padding(top = 6.dp)) {
                        if (!landscape) PrimaryButton("Portrait", Modifier.weight(1f)) { landscape = false }
                        else SecondaryButton("Portrait", Modifier.weight(1f)) { landscape = false }
                        Spacer(Modifier.width(8.dp))
                        if (landscape) PrimaryButton("Landscape", Modifier.weight(1f)) { landscape = true }
                        else SecondaryButton("Landscape", Modifier.weight(1f)) { landscape = true }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onCreate(name, landscape) }) { Text("Create", color = Tokens.primary, fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Cancel", color = Tokens.text, fontSize = 16.sp) }
            },
        )
    }

    @Composable
    private fun SetupScreen(onBack: () -> Unit) {
        var guide by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
            if (!accessibilityOn) {
                TextButton(onClick = { guide = !guide }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (guide) "Hide the steps" else "Switch is grey, or says \"Restricted setting\"? Show me how", color = Tokens.primary, fontSize = 15.sp)
                }
                if (guide) RestrictedGuide()
            }
            val others = remember(accessibilityOn) { otherAccessibilityApps() }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Other apps with accessibility on", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text)
                        Text(others.joinToString(", "), fontSize = 14.sp, color = Tokens.primary, modifier = Modifier.padding(top = 2.dp))
                        Text(
                            "If MacroLoop's taps act strangely, turn the other apps off in Accessibility settings. Two apps that tap or draw over the screen at the same time can get in each other's way.",
                            fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
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

    /** Step by step for the "Restricted setting" block Android puts on apps that were not installed from the Play Store. */
    @Composable
    private fun RestrictedGuide() {
        Card(
            colors = CardDefaults.cardColors(containerColor = Tokens.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Android blocks apps that came from a file", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text)
                Text(
                    "This is normal for an app that is not from the Play Store. Do these in order:",
                    fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                val steps = listOf(
                    "Tap \"Open accessibility settings\" above, find MacroLoop under Downloaded apps (or Installed apps) and tap it once. A message says it is restricted. This makes the option appear in the next step.",
                    "Tap \"Open MacroLoop's App info\" below.",
                    "Tap the three dots at the top right, then \"Allow restricted settings\". Confirm with your PIN or fingerprint if asked.",
                    "Close Settings completely: open the recent apps screen and swipe Settings away.",
                    "Come back here, tap \"Open accessibility settings\" again, tap MacroLoop and switch it On.",
                    "Still grey? Restart the phone, then repeat step 5.",
                )
                steps.forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", fontSize = 15.sp, color = Tokens.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                        Text(t, fontSize = 15.sp, color = Tokens.text)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Open MacroLoop's App info", Modifier.fillMaxWidth()) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    @Composable
    private fun RunLogScreen(onBack: () -> Unit) {
        var open by remember { mutableStateOf(-1L) }
        val fmt = remember { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()) }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Run history", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Tokens.text)
            Text(
                "The last ${RunLog.KEEP} runs. When a run stops with a problem, a picture of the screen at that moment is kept. Tap a run to see it.",
                fontSize = 14.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            if (RunLog.entries.isEmpty()) {
                Text("Nothing yet. Runs appear here after you press Play.", fontSize = 16.sp, color = Tokens.textMuted)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RunLog.entries.toList(), key = { it.time }) { e ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Tokens.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { open = if (open == e.time) -1L else e.time },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${e.macro}  ·  ${fmt.format(java.util.Date(e.time))}", fontSize = 15.sp, color = Tokens.text, fontWeight = FontWeight.SemiBold)
                            Text(e.result, fontSize = 14.sp, color = if (e.problem) Tokens.danger else Tokens.textMuted, modifier = Modifier.padding(top = 2.dp))
                            Text("${e.seconds} s", fontSize = 12.sp, color = Tokens.textMuted)
                            if (open == e.time && e.detail.isNotBlank()) {
                                Text("What happened (latest at the bottom):", fontSize = 12.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 8.dp))
                                Text(e.detail, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Tokens.text, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (open == e.time) {
                                val shot = remember(e.shot) {
                                    RunLog.shotFile(e)?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
                                }
                                if (shot != null) {
                                    Text("The screen when it stopped:", fontSize = 12.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 8.dp))
                                    Image(bitmap = shot, contentDescription = "The screen when the run stopped", contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 4.dp))
                                } else if (e.problem) {
                                    Text("No picture was kept (screen capture was off).", fontSize = 12.sp, color = Tokens.textMuted, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                SecondaryButton("Back to my macros", Modifier.weight(1f)) { onBack() }
                if (RunLog.entries.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Clear", Modifier) { RunLog.clear() }
                }
            }
            Spacer(Modifier.height(12.dp))
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
        if (mode == 1 && m.runOnly) {
            Toast.makeText(this, "This macro is run only. You can play it but not edit it.", Toast.LENGTH_LONG).show()
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
