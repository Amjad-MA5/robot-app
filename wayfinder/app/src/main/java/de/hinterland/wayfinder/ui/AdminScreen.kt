package de.hinterland.wayfinder.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hinterland.wayfinder.BuildConfig
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.Floors
import de.hinterland.wayfinder.core.Room
import de.hinterland.wayfinder.core.RoomIcon
import de.hinterland.wayfinder.core.Settings
import de.hinterland.wayfinder.flow.GuideViewModel
import de.hinterland.wayfinder.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Staff settings. Changes take effect after "Save". */
@Composable
fun AdminScreen(vm: GuideViewModel, onClose: () -> Unit) {
    // The robot's display is only about 548 dp wide: draw the staff screen a bit smaller there
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Night)) {
        val base = LocalDensity.current
        val scale = if (maxWidth < 700.dp) 0.72f else 1f
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            AdminContent(vm, onClose)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminContent(vm: GuideViewModel, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(vm.settings) }
    var places by remember { mutableStateOf(vm.knownPlaces()) }
    val robotState by vm.robot.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val log by AppLog.recent.collectAsState()
    val scope = rememberCoroutineScope()
    var driveJob by remember { mutableStateOf<Job?>(null) }
    var driveResult by remember { mutableStateOf("") }
    var testPoint by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            places = vm.knownPlaces()
            delay(2_000)
        }
    }
    BackHandler { onClose() }

    val pointNames = places.orEmpty().filter { !it.isSystemPoint }.map { it.name }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Night)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wayfinder · staff settings", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = {
                vm.saveSettings(draft)
                onClose()
            }) { Text("Save") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onClose) { Text("Cancel", color = Palette.Text) }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { (context as? Activity)?.finishAffinity() }) { Text("Exit app", color = Palette.Stop) }
        }

        // ------------------------------------------------------------ status
        Section("Status") {
            val wanderBlocker = vm.wanderBlocker()
            Info("Robot", if (robotState.simulated) "simulation (no robot found)" else if (robotState.connected) "connected" else "not connected")
            if (robotState.suspended) Info("Control", "taken over by RobotOS")
            Info("Map points", places?.let { list -> if (list.isEmpty()) "none" else list.joinToString { it.name + if (it.reachable) "" else " (unreachable)" } } ?: "not loaded yet")
            Info("Battery", ui.battery?.let { "$it %" } ?: "unknown")
            Info("Wandering", wanderBlocker ?: "ready")
            Info("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.reloadPlaces() }) { Text("Reload map points", color = Palette.Text) }
                OutlinedButton(onClick = {
                    vm.saveSettings(draft)
                    onClose()
                    vm.startWanderingNow()
                }) { Text("Save & start wandering", color = Palette.Text) }
            }
        }

        // ------------------------------------------------------------ test
        Section("Test") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PointField("Drive to point", testPoint, pointNames, Modifier.weight(1f)) { testPoint = it }
                Spacer(Modifier.width(6.dp))
                Button(enabled = testPoint.isNotBlank() && driveJob?.isActive != true, onClick = {
                    driveJob = scope.launch {
                        driveResult = "Driving to $testPoint…"
                        driveResult = vm.testDrive(testPoint)
                    }
                }) { Text("Drive") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        driveJob?.cancel()
                        vm.robot.stopNavigation()
                        driveResult = "Stopped"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Stop),
                ) { Text("Stop") }
            }
            if (driveResult.isNotBlank()) Text(driveResult, color = Palette.Accent, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { sentence = it },
                    label = { Text("Try a sentence (as if a visitor said it)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Button(enabled = sentence.isNotBlank(), onClick = {
                    vm.saveSettings(draft)
                    onClose()
                    vm.onSpeech(sentence, true)
                }) { Text("Try") }
            }
        }

        // ------------------------------------------------------------ rooms
        Section("Rooms on the screen") {
            SwitchRow("Also show every point of the robot's map as a room", draft.autoAddMapPoints) { draft = draft.copy(autoAddMapPoints = it) }
            Text(
                "Rooms below come first. A room needs a map point to be walkable; a room on another floor is " +
                    "announced with its floor (the robot can walk visitors to the stairs point).",
                color = Palette.Muted, fontSize = 12.sp,
            )
            draft.rooms.forEachIndexed { index, room ->
                RoomEditor(
                    room = room,
                    pointNames = pointNames,
                    onChange = { changed -> draft = draft.copy(rooms = draft.rooms.toMutableList().also { it[index] = changed }) },
                    onUp = if (index > 0) ({ draft = draft.copy(rooms = draft.rooms.swap(index, index - 1)) }) else null,
                    onDown = if (index < draft.rooms.lastIndex) ({ draft = draft.copy(rooms = draft.rooms.swap(index, index + 1)) }) else null,
                    onDelete = { draft = draft.copy(rooms = draft.rooms.filterIndexed { i, _ -> i != index }) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { draft = draft.copy(rooms = draft.rooms + Room(id = Settings.newRoomId("room"), name = "New room")) }) { Text("+ Add room") }
                if (draft.rooms.none { it.id == Settings.RESTROOM_ID }) {
                    OutlinedButton(onClick = { draft = draft.copy(rooms = Settings.defaultRooms() + draft.rooms) }) { Text("+ Restrooms (floors 1 and −1)", color = Palette.Text) }
                }
            }
            if (draft.autoAddMapPoints && pointNames.isNotEmpty()) {
                Text("Map points shown as rooms:", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                pointNames.forEach { name ->
                    SwitchRow(name, name !in draft.hiddenMapPoints) { show ->
                        draft = draft.copy(hiddenMapPoints = if (show) draft.hiddenMapPoints - name else draft.hiddenMapPoints + name)
                    }
                }
            }
        }

        // ------------------------------------------------------------ floors and places
        Section("Floors and special points") {
            NumberField("Floor the robot is on (0 = ground floor)", draft.robotFloor, -9..99) { draft = draft.copy(robotFloor = it) }
            PointField("Stairs / elevator point (for rooms on other floors)", draft.stairsPoint, pointNames) { draft = draft.copy(stairsPoint = it) }
            PointField(
                "Home point (\"\" = RobotOS reception point, \"-\" = none)", draft.homePoint, listOf("", Settings.HOME_NONE) + pointNames,
            ) { draft = draft.copy(homePoint = it) }
        }

        // ------------------------------------------------------------ wandering
        Section("Wandering (shows presence when nobody needs help)") {
            SwitchRow("Wander between map points", draft.wanderEnabled) { draft = draft.copy(wanderEnabled = it) }
            SwitchRow("Use all map points", draft.wanderPoints == null) { all ->
                draft = draft.copy(wanderPoints = if (all) null else pointNames)
            }
            draft.wanderPoints?.let { chosen ->
                (pointNames + chosen.filter { it !in pointNames }).forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = name in chosen, onCheckedChange = { on ->
                            val updated = if (on) pointNames.filter { it in chosen || it == name } + chosen.filter { it !in pointNames } else chosen - name
                            draft = draft.copy(wanderPoints = updated)
                        })
                        Text(name, color = Palette.Text, fontSize = 13.sp)
                    }
                }
            }
            SwitchRow("Random order", draft.wanderRandom) { draft = draft.copy(wanderRandom = it) }
            NumberField("Pause at each point (seconds)", draft.wanderPauseSec, 0..600) { draft = draft.copy(wanderPauseSec = it) }
            NumberField("Start wandering after no visitor for (seconds)", draft.idleBeforeWanderSec, 10..3600) { draft = draft.copy(idleBeforeWanderSec = it) }
            SliderRow("Wander speed", draft.wanderSpeed, 0.2..0.7, "m/s") { draft = draft.copy(wanderSpeed = it) }
        }

        // ------------------------------------------------------------ guiding and people
        Section("Guiding and visitors") {
            SliderRow("Guiding speed", draft.guideSpeed, 0.2..0.8, "m/s") { draft = draft.copy(guideSpeed = it) }
            NumberField("Stay at the destination (seconds)", draft.arrivalWaitSec, 0..120) { draft = draft.copy(arrivalWaitSec = it) }
            SwitchRow("Say hello when someone stands in front of me", draft.greetPeople) { draft = draft.copy(greetPeople = it) }
            SliderRow("Greeting distance", draft.greetDistance, 0.5..3.0, "m") { draft = draft.copy(greetDistance = it) }
            SwitchRow("Invite people nearby while wandering (every 3 min at most)", draft.announcements) { draft = draft.copy(announcements = it) }
        }

        // ------------------------------------------------------------ battery
        Section("Battery") {
            NumberField("Stop wandering below (percent)", draft.wanderMinBattery, 0..90) { draft = draft.copy(wanderMinBattery = it) }
            NumberField("Drive to the charging pile below (percent, 0 = never)", draft.autoChargeBelow, 0..50) { draft = draft.copy(autoChargeBelow = it) }
        }

        // ------------------------------------------------------------ voice
        Section("Voice and personality") {
            TextRow("Robot name", draft.robotName) { draft = draft.copy(robotName = it) }
            TextRow("Venue", draft.venueName) { draft = draft.copy(venueName = it) }
            SwitchRow("Use the AgentOS language model for free questions and small talk", draft.useLlm) { draft = draft.copy(useLlm = it) }
            TextRow("Event facts for small talk (e.g. \"Pitches start at 2 pm in the Main Hall\")", draft.eventInfo, singleLine = false) {
                draft = draft.copy(eventInfo = it)
            }
            TextRow("Admin PIN", draft.adminPin) { draft = draft.copy(adminPin = it.filter(Char::isDigit).take(12)) }
        }

        // ------------------------------------------------------------ log
        Section("Log (newest first)") {
            Text(
                log.takeLast(80).reversed().joinToString("\n").ifEmpty { "(empty)" },
                color = Palette.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 12.sp,
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().also {
    val t = it[a]
    it[a] = it[b]
    it[b] = t
}

// ====================================================================== room editor

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomEditor(
    room: Room,
    pointNames: List<String>,
    onChange: (Room) -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var floorsText by remember(room.id) { mutableStateOf(Floors.format(room.floors)) }
    var aliasesText by remember(room.id) { mutableStateOf(room.aliases.joinToString(", ")) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(room.resolvedIcon.emoji, fontSize = 20.sp)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = room.name,
                onValueChange = { onChange(room.copy(name = it)) },
                label = { Text("Name on the screen") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text("Show", color = Palette.Muted, fontSize = 12.sp)
            Switch(checked = room.visible, onCheckedChange = { onChange(room.copy(visible = it)) })
        }
        Row {
            PointField("Map point the robot walks to", room.mapPoint, pointNames, Modifier.weight(1f)) { onChange(room.copy(mapPoint = it)) }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = floorsText,
                onValueChange = {
                    floorsText = it
                    onChange(room.copy(floors = Floors.parse(it)))
                },
                label = { Text("Floors") },
                placeholder = { Text("1, -1 (empty = mine)") },
                singleLine = true,
                modifier = Modifier.width(150.dp),
            )
        }
        OutlinedTextField(
            value = room.directions,
            onValueChange = { onChange(room.copy(directions = it)) },
            label = { Text("Directions (spoken), e.g. \"Take the stairs next to the entrance.\"") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = aliasesText,
            onValueChange = {
                aliasesText = it
                onChange(room.copy(aliases = it.split(',').map(String::trim).filter(String::isNotEmpty)))
            },
            label = { Text("Other words visitors use (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
            IconChoice(room.icon) { onChange(room.copy(icon = it)) }
            TextButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Text("▲ Up") }
            TextButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Text("▼ Down") }
            TextButton(onClick = onDelete) { Text("Delete", color = Palette.Stop) }
        }
    }
}

@Composable
private fun IconChoice(current: RoomIcon, onPick: (RoomIcon) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Icon: ${if (current == RoomIcon.AUTO) "automatic" else current.emoji}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RoomIcon.entries.forEach { icon ->
                DropdownMenuItem(
                    text = { Text(if (icon == RoomIcon.AUTO) "Automatic" else "${icon.emoji}  ${icon.name.lowercase().replaceFirstChar { it.titlecase() }}") },
                    onClick = {
                        open = false
                        onPick(icon)
                    },
                )
            }
        }
    }
}

// ====================================================================== small building blocks

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Tile, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text("$label: ", color = Palette.Muted, fontSize = 13.sp)
        Text(value, color = Palette.Text, fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextRow(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == '-' }.take(5)
            text.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SliderRow(label: String, value: Double, range: ClosedFloatingPointRange<Double>, unit: String, onChange: (Double) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)} $unit", color = Palette.Text, fontSize = 13.sp)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it * 20).roundToInt() / 20.0) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

/** Text field for a map point name, with a list of the points on the current map. */
@Composable
private fun PointField(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { open = true }, enabled = options.isNotEmpty()) { Text("▼") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                "" -> "(empty)"
                                Settings.HOME_NONE -> "- (none)"
                                else -> option
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

