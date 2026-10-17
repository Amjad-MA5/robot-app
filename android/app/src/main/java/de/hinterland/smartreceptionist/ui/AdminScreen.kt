package de.hinterland.smartreceptionist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hinterland.smartreceptionist.BuildConfig
import de.hinterland.smartreceptionist.badge.CameraConfig
import de.hinterland.smartreceptionist.badge.RobotCamera
import de.hinterland.smartreceptionist.config.AppSettings
import de.hinterland.smartreceptionist.net.AppUpdater
import de.hinterland.smartreceptionist.net.BackendClient
import de.hinterland.smartreceptionist.robot.NavResult
import de.hinterland.smartreceptionist.robot.RobotController
import de.hinterland.smartreceptionist.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun PinDialog(expectedPin: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin area") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; wrong = false },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text("Incorrect PIN", color = Palette.Error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pin == expectedPin) onSuccess() else wrong = true }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(initial: AppSettings, onSave: (AppSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(initial) }
    var places by remember { mutableStateOf<List<String>>(emptyList()) }
    var log by remember { mutableStateOf("Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})") }
    var driveJob by remember { mutableStateOf<Job?>(null) }

    fun addLog(line: String) { log = "$line\n$log".take(2000) }
    fun client() = BackendClient(s.backendUrl, s.apiKey)

    // Eigene Testkamera für den Admin-Bereich – nutzt die (noch ungespeicherten) Einstellungen
    val currentSettings by rememberUpdatedState(s)
    val testCamera = remember {
        RobotCamera(context) { CameraConfig(currentSettings.cameraRotation, currentSettings.cameraSource) }
    }
    val testFrame by testCamera.preview.collectAsState()
    DisposableEffect(Unit) { onDispose { testCamera.close() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚙️ Settings", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close", color = Palette.Text) }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { onSave(s); addLog("✔ Saved") }) { Text("Save") }
        }

        Section("Backend")
        Field("Backend URL (e.g. http://192.168.2.50:8080)", s.backendUrl) { s = s.copy(backendUrl = it) }
        Field("API key", s.apiKey) { s = s.copy(apiKey = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Test connection") {
                scope.launch {
                    runCatching { client().health() }
                        .onSuccess { h ->
                            addLog("✔ Backend reachable: ${h.company} (mode ${h.mode})")
                            runCatching { client().allEmployees() }
                                .onSuccess { addLog("✔ API key OK, ${it.size} employees") }
                                .onFailure { addLog("✖ API key/directory: ${it.message}") }
                        }
                        .onFailure { addLog("✖ Backend: ${it.message}") }
                }
            }
            SmallButton("Check for app updates") {
                scope.launch {
                    addLog("… checking for updates")
                    when (val r = AppUpdater.checkAndInstall(context, client()) { }) {
                        is AppUpdater.Result.UpToDate -> addLog("✔ Up to date (version ${r.version})")
                        is AppUpdater.Result.Started -> addLog("⬇ ${r.how}")
                        is AppUpdater.Result.Failed -> addLog("✖ ${r.reason}")
                    }
                }
            }
        }

        Section("Robot & map")
        Text(
            "RobotAPI: ${if (RobotController.connected.value) "connected" else "not connected"} · " +
                "Localized: ${if (RobotController.isLocalized()) "yes" else "no/unknown"}",
            color = Palette.Muted, fontSize = 12.sp,
        )
        SmallButton("Load map points") {
            scope.launch {
                places = RobotController.placeNames()
                addLog(if (places.isEmpty()) "✖ No map points (is RobotAPI connected and a map loaded?)" else "✔ Loaded ${places.size} map points")
            }
        }
        Field("Waiting room map point", s.waitingRoom) { s = s.copy(waitingRoom = it) }
        if (places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p -> FilterChip(selected = s.waitingRoom == p, onClick = { s = s.copy(waitingRoom = p) }, label = { Text(p, fontSize = 11.sp) }) }
            }
        }
        Field("Reception map point (return)", s.receptionPoint) { s = s.copy(receptionPoint = it) }
        if (places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p -> FilterChip(selected = s.receptionPoint == p, onClick = { s = s.copy(receptionPoint = p) }, label = { Text(p, fontSize = 11.sp) }) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Test drive → waiting room") {
                driveJob?.cancel()
                driveJob = scope.launch {
                    addLog("… navigating to ${s.waitingRoom}")
                    addLog(describe(RobotController.navigateTo(s.waitingRoom, s.escortSpeed)))
                }
            }
            SmallButton("Test drive → reception") {
                driveJob?.cancel()
                driveJob = scope.launch {
                    addLog("… navigating to ${s.receptionPoint}")
                    addLog(describe(RobotController.navigateTo(s.receptionPoint, s.returnSpeed)))
                }
            }
            SmallButton("Stop driving") { driveJob?.cancel(); RobotController.stopNavigation(); addLog("■ Stopped") }
        }
        // Wegweiser: jeden Kartenpunkt einzeln anfahren (nach "Load map points")
        if (places.isNotEmpty()) {
            Text("Test drive to any map point (guide feature):", color = Palette.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p ->
                    SmallButton("→ $p") {
                        driveJob?.cancel()
                        driveJob = scope.launch {
                            addLog("… navigating to $p")
                            addLog(describe(RobotController.navigateTo(p, s.escortSpeed)))
                        }
                    }
                }
            }
        }
        LabeledSlider("Escort speed: %.2f m/s".format(s.escortSpeed), s.escortSpeed, 0.2f..0.85f) { s = s.copy(escortSpeed = it) }
        LabeledSlider("Return speed: %.2f m/s".format(s.returnSpeed), s.returnSpeed, 0.2f..0.85f) { s = s.copy(returnSpeed = it) }
        LabeledSlider("Greeting distance: %.1f m".format(s.greetDistance), s.greetDistance, 0.8f..3.5f) { s = s.copy(greetDistance = it) }
        LabeledSlider("Waiting room pause: ${s.waitingRoomPauseSec} s", s.waitingRoomPauseSec.toFloat(), 0f..15f) {
            s = s.copy(waitingRoomPauseSec = it.toInt())
        }

        Section("Visitor badge & camera")
        ToggleRow("Print a visitor badge with photo for guests with an appointment", s.badgeEnabled) { s = s.copy(badgeEnabled = it) }
        Field("Where to pick up the badge (e.g. \"am Drucker neben dem Empfang\")", s.badgePickupHint) { s = s.copy(badgePickupHint = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Camera:", color = Palette.Text, fontSize = 12.sp)
            listOf("auto" to "Auto", "shared" to "Robot stream", "camera2" to "Android camera").forEach { (value, label) ->
                FilterChip(selected = s.cameraSource == value, onClick = { s = s.copy(cameraSource = value) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rotation:", color = Palette.Text, fontSize = 12.sp)
            listOf(-1, 0, 90, 180, 270).forEach { deg ->
                FilterChip(
                    selected = s.cameraRotation == deg,
                    onClick = { s = s.copy(cameraRotation = deg) },
                    label = { Text(if (deg < 0) "Auto" else "$deg°", fontSize = 11.sp) },
                )
            }
        }
        ToggleRow("Mirror the live preview (guest sees himself like in a mirror)", s.cameraMirror) { s = s.copy(cameraMirror = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Start camera test") {
                scope.launch {
                    addLog("… opening camera (${s.cameraSource}, rotation ${if (s.cameraRotation < 0) "auto" else s.cameraRotation})")
                    addLog(if (testCamera.open()) "✔ Camera running: ${testCamera.source}" else "✖ No camera available (permission? busy?)")
                }
            }
            SmallButton("Stop camera") { testCamera.close(); addLog("■ Camera stopped") }
            SmallButton("Printer status") {
                scope.launch { addLog(runCatching { "🖨 " + client().printerStatus() }.getOrElse { "✖ Printer: ${it.message}" }) }
            }
            SmallButton("Print test badge") {
                scope.launch { addLog(runCatching { "🖨 Test badge " + client().printerTest() }.getOrElse { "✖ Printer: ${it.message}" }) }
            }
        }
        testFrame?.let { frame ->
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Camera preview",
                modifier = Modifier
                    .height(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .graphicsLayer { scaleX = if (s.cameraMirror) -1f else 1f },
            )
            Text("Change rotation/source, then press \"Start camera test\" again.", color = Palette.Muted, fontSize = 11.sp)
        }

        Section("Conversation")
        ToggleRow("Greet automatically when someone is in front", s.autoGreet) { s = s.copy(autoGreet = it) }
        ToggleRow("Use AgentOS language model for understanding", s.useLlm) { s = s.copy(useLlm = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Language:", color = Palette.Text, fontSize = 13.sp)
            FilterChip(selected = s.language == "de", onClick = { s = s.copy(language = "de") }, label = { Text("German") })
            FilterChip(selected = s.language == "en", onClick = { s = s.copy(language = "en") }, label = { Text("English") })
        }
        Field("Robot name", s.robotName) { s = s.copy(robotName = it) }
        Field("Company name (fallback; otherwise from backend)", s.companyName) { s = s.copy(companyName = it) }
        Field("Admin PIN", s.adminPin) { s = s.copy(adminPin = it) }

        Section("Log")
        Text(log, color = Palette.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(30.dp))
    }
}

private fun describe(r: NavResult): String = when (r) {
    NavResult.Arrived -> "✔ Arrived"
    NavResult.Cancelled -> "■ Cancelled"
    is NavResult.Failed -> "✖ ${r.message} (code ${r.code})"
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title, color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.PanelStrong, contentColor = Palette.Text),
    ) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Palette.Text, fontSize = 12.sp)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
