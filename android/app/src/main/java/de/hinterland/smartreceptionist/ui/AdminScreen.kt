package de.hinterland.smartreceptionist.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hinterland.smartreceptionist.BuildConfig
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
        title = { Text("Admin-Bereich") },
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
                if (wrong) Text("Falsche PIN", color = Palette.Error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pin == expectedPin) onSuccess() else wrong = true }) { Text("Öffnen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚙️ Einstellungen", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Schließen", color = Palette.Text) }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { onSave(s); addLog("✔ Gespeichert") }) { Text("Speichern") }
        }

        Section("Backend")
        Field("Backend-URL (z. B. http://192.168.2.50:8080)", s.backendUrl) { s = s.copy(backendUrl = it) }
        Field("API-Key", s.apiKey) { s = s.copy(apiKey = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Verbindung testen") {
                scope.launch {
                    runCatching { client().health() }
                        .onSuccess { h ->
                            addLog("✔ Backend erreichbar: ${h.company} (Modus ${h.mode})")
                            runCatching { client().allEmployees() }
                                .onSuccess { addLog("✔ API-Key ok, ${it.size} Mitarbeitende") }
                                .onFailure { addLog("✖ API-Key/Verzeichnis: ${it.message}") }
                        }
                        .onFailure { addLog("✖ Backend: ${it.message}") }
                }
            }
            SmallButton("App-Update prüfen") {
                scope.launch {
                    addLog("… prüfe Update")
                    when (val r = AppUpdater.checkAndInstall(context, client()) { }) {
                        is AppUpdater.Result.UpToDate -> addLog("✔ Aktuell (Version ${r.version})")
                        is AppUpdater.Result.Started -> addLog("⬇ ${r.how}")
                        is AppUpdater.Result.Failed -> addLog("✖ ${r.reason}")
                    }
                }
            }
        }

        Section("Roboter & Karte")
        Text(
            "RobotAPI: ${if (RobotController.connected.value) "verbunden" else "nicht verbunden"} · " +
                "Lokalisiert: ${if (RobotController.isLocalized()) "ja" else "nein/unbekannt"}",
            color = Palette.Muted, fontSize = 12.sp,
        )
        SmallButton("Kartenpunkte laden") {
            scope.launch {
                places = RobotController.placeNames()
                addLog(if (places.isEmpty()) "✖ Keine Kartenpunkte (RobotAPI verbunden? Karte geladen?)" else "✔ ${places.size} Kartenpunkte geladen")
            }
        }
        Field("Kartenpunkt Warteraum", s.waitingRoom) { s = s.copy(waitingRoom = it) }
        if (places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p -> FilterChip(selected = s.waitingRoom == p, onClick = { s = s.copy(waitingRoom = p) }, label = { Text(p, fontSize = 11.sp) }) }
            }
        }
        Field("Kartenpunkt Empfang (Rückkehr)", s.receptionPoint) { s = s.copy(receptionPoint = it) }
        if (places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p -> FilterChip(selected = s.receptionPoint == p, onClick = { s = s.copy(receptionPoint = p) }, label = { Text(p, fontSize = 11.sp) }) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Testfahrt → Warteraum") {
                driveJob?.cancel()
                driveJob = scope.launch {
                    addLog("… fahre zu ${s.waitingRoom}")
                    addLog(describe(RobotController.navigateTo(s.waitingRoom, s.escortSpeed)))
                }
            }
            SmallButton("Testfahrt → Empfang") {
                driveJob?.cancel()
                driveJob = scope.launch {
                    addLog("… fahre zu ${s.receptionPoint}")
                    addLog(describe(RobotController.navigateTo(s.receptionPoint, s.returnSpeed)))
                }
            }
            SmallButton("Fahrt stoppen") { driveJob?.cancel(); RobotController.stopNavigation(); addLog("■ gestoppt") }
        }
        LabeledSlider("Begleit-Tempo: %.2f m/s".format(s.escortSpeed), s.escortSpeed, 0.2f..0.85f) { s = s.copy(escortSpeed = it) }
        LabeledSlider("Rückfahrt-Tempo: %.2f m/s".format(s.returnSpeed), s.returnSpeed, 0.2f..0.85f) { s = s.copy(returnSpeed = it) }
        LabeledSlider("Begrüßungs-Abstand: %.1f m".format(s.greetDistance), s.greetDistance, 0.8f..3.5f) { s = s.copy(greetDistance = it) }
        LabeledSlider("Pause im Warteraum: ${s.waitingRoomPauseSec} s", s.waitingRoomPauseSec.toFloat(), 0f..15f) {
            s = s.copy(waitingRoomPauseSec = it.toInt())
        }

        Section("Dialog")
        ToggleRow("Automatisch begrüßen, wenn jemand davor steht", s.autoGreet) { s = s.copy(autoGreet = it) }
        ToggleRow("Sprachmodell (AgentOS LLM) zum Verstehen nutzen", s.useLlm) { s = s.copy(useLlm = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sprache:", color = Palette.Text, fontSize = 13.sp)
            FilterChip(selected = s.language == "de", onClick = { s = s.copy(language = "de") }, label = { Text("Deutsch") })
            FilterChip(selected = s.language == "en", onClick = { s = s.copy(language = "en") }, label = { Text("English") })
        }
        Field("Name des Roboters", s.robotName) { s = s.copy(robotName = it) }
        Field("Firmenname (Fallback, sonst vom Backend)", s.companyName) { s = s.copy(companyName = it) }
        Field("Admin-PIN", s.adminPin) { s = s.copy(adminPin = it) }

        Section("Protokoll")
        Text(log, color = Palette.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(30.dp))
    }
}

private fun describe(r: NavResult): String = when (r) {
    NavResult.Arrived -> "✔ angekommen"
    NavResult.Cancelled -> "■ abgebrochen"
    is NavResult.Failed -> "✖ ${r.message} (Code ${r.code})"
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
