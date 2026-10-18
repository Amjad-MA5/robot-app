package de.hinterland.wayfinder.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hinterland.wayfinder.core.Lines
import de.hinterland.wayfinder.flow.FloorBadge
import de.hinterland.wayfinder.flow.Mode
import de.hinterland.wayfinder.flow.Tile
import de.hinterland.wayfinder.flow.UiState
import de.hinterland.wayfinder.ui.theme.Palette
import kotlin.math.min

/** The layout is designed for 548 × 308 dp (head display of the robot, 1920 × 1080) and scaled to fit. */
private const val DESIGN_WIDTH_DP = 548f
private const val DESIGN_HEIGHT_DP = 308f

@Composable
fun ScaledToRobotScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = min(maxWidth.value / DESIGN_WIDTH_DP, maxHeight.value / DESIGN_HEIGHT_DP).coerceIn(0.6f, 3f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, 1f)) {
            content()
        }
    }
}

class MainActions(
    val onAnyTouch: () -> Unit,
    val onRoom: (String) -> Unit,
    val onStop: () -> Unit,
    val onWay: () -> Unit,
    val onCloseCard: () -> Unit,
    val onAdmin: () -> Unit,
)

@Composable
fun MainScreen(state: UiState, actions: MainActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            // Every touch counts as "someone is here" and stops a wandering robot. Seen first, not consumed,
            // so the tile below still receives the tap.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    actions.onAnyTouch()
                }
            },
    ) {
        when (state.mode) {
            Mode.PAUSED, Mode.CHARGING -> StatusScreen(state)
            else -> Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                FacePanel(state, Modifier.width(158.dp).fillMaxHeight())
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Each panel keeps the state it was shown with while it fades out
                    AnimatedContent(
                        targetState = state,
                        contentKey = { panelOf(it.mode) },
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        modifier = Modifier.weight(1f),
                        label = "content",
                    ) { shown ->
                        when (panelOf(shown.mode)) {
                            Panel.GUIDING -> GuidingPanel(shown, actions)
                            Panel.INFO -> InfoPanel(shown, actions)
                            Panel.ARRIVED -> ArrivedPanel(shown, actions)
                            Panel.ROOMS -> RoomsPanel(shown, actions)
                        }
                    }
                    CaptionBar(state)
                }
            }
        }
        if (state.mode == Mode.PAUSED || state.mode == Mode.CHARGING) {
            state.banner?.let { Banner(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) }
        }
        state.battery?.let { BatteryChip(it, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 6.dp)) }
        // Hidden admin entry: long-press the top-left corner
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(46.dp)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { actions.onAdmin() }) },
        )
    }
}

private enum class Panel { ROOMS, GUIDING, INFO, ARRIVED }

private fun panelOf(mode: Mode) = when (mode) {
    Mode.GUIDING -> Panel.GUIDING
    Mode.INFO -> Panel.INFO
    Mode.ARRIVED -> Panel.ARRIVED
    else -> Panel.ROOMS
}

// ====================================================================== face

@Composable
private fun FacePanel(state: UiState, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        RobotFace(state.mood, state.listening, Modifier.size(140.dp))
        Spacer(Modifier.height(4.dp))
        Text(state.robotName, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        val status = when {
            state.listening && (state.mode == Mode.HOME || state.mode == Mode.INFO) -> Lines.statusListening
            state.mood == de.hinterland.wayfinder.flow.Mood.THINKING -> Lines.statusThinking
            else -> state.status
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.listening) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Go))
                Spacer(Modifier.width(4.dp))
            }
            Text(status, color = Palette.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Hints for the staff (demo mode, not localized, low battery ...) - under the face, out of the visitors' way
        state.banner?.let {
            Spacer(Modifier.height(8.dp))
            Banner(it, Modifier)
        }
    }
}

// ====================================================================== rooms

@Composable
private fun RoomsPanel(state: UiState, actions: MainActions) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(4.dp))
        Text(state.title, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (state.tiles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(Lines.noRooms, color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        } else {
            val columns = if (state.tiles.size <= 4) 2 else 3
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(state.tiles, key = { it.id }) { tile ->
                    RoomTile(tile, tall = state.tiles.size <= 4) { actions.onRoom(tile.id) }
                }
            }
        }
        if (state.mode == Mode.HOME) {
            Text(Lines.homeHint, color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RoomTile(tile: Tile, tall: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tall) 84.dp else 70.dp)
            .clip(shape)
            .background(Palette.Tile)
            .border(1.dp, Palette.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tile.emoji, fontSize = if (tall) 22.sp else 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                tile.name,
                color = Palette.Text,
                fontSize = if (tall) 14.sp else 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (tall) 16.sp else 14.sp,
            )
        }
        Text(
            if (tile.walkable) "${tile.hint}  →" else tile.hint,
            color = if (tile.walkable) Palette.Accent else Palette.Floor,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ====================================================================== guiding

@Composable
private fun GuidingPanel(state: UiState, actions: MainActions) {
    val room = state.focus
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(state.title, color = Palette.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.focusEmoji, fontSize = 34.sp)
            Spacer(Modifier.width(8.dp))
            Text(room?.name.orEmpty(), color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
        }
        if (state.infoText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.infoText, color = Palette.Floor, fontSize = 12.sp, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalkingDots()
            Spacer(Modifier.width(10.dp))
            Text(
                state.navNote.ifBlank { "On my way" },
                color = if (state.navNote.isBlank()) Palette.Accent else Palette.Warn,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = actions.onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Stop, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(50.dp).width(132.dp),
            ) {
                Text("■  ${Lines.stop}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WalkingDots() {
    val t = rememberInfiniteTransition(label = "walk")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Row {
        for (i in 0 until 3) {
            val active = phase.toInt() == i
            Text("›", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(if (active) 1f else 0.3f))
        }
    }
}

// ====================================================================== answer card (floors)

@Composable
private fun InfoPanel(state: UiState, actions: MainActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.focusEmoji, fontSize = 28.sp)
            Spacer(Modifier.width(8.dp))
            Text(state.title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.floors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.floors.forEach { FloorCard(it) }
            }
        }
        if (state.infoText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.infoText, color = Palette.Text, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.wayButton?.let { label ->
                Button(
                    onClick = actions.onWay,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Text("$label  →", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = actions.onCloseCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 42.dp)) {
                Text(Lines.close, color = Palette.Text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FloorCard(badge: FloorBadge) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(96.dp)
            .clip(shape)
            .background(Palette.TileStrong)
            .border(1.5.dp, Palette.Floor.copy(alpha = 0.7f), shape)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(badge.label, color = Palette.Floor, fontSize = 30.sp, fontWeight = FontWeight.Black)
            badge.up?.let {
                Spacer(Modifier.width(4.dp))
                Text(if (it) "↑" else "↓", color = Palette.Floor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(badge.caption, color = Palette.Text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ====================================================================== arrived

@Composable
private fun ArrivedPanel(state: UiState, actions: MainActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Go), contentAlignment = Alignment.Center) {
                Text("✓", color = Palette.AccentDark, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text(state.title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.focusEmoji, fontSize = 24.sp)
            Spacer(Modifier.width(6.dp))
            Text(state.focus?.name.orEmpty(), color = Palette.Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.floors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { state.floors.forEach { FloorCard(it) } }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = actions.onCloseCard, shape = RoundedCornerShape(14.dp)) {
            Text(Lines.anythingElse, color = Palette.Text, fontSize = 13.sp)
        }
    }
}

// ====================================================================== paused / charging

@Composable
private fun StatusScreen(state: UiState) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        RobotFace(state.mood, listening = false, modifier = Modifier.size(150.dp))
        Spacer(Modifier.height(8.dp))
        Text(state.title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (state.infoText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.infoText, color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

// ====================================================================== small parts

@Composable
private fun CaptionBar(state: UiState) {
    val caption = state.caption
    val heard = state.heard
    AnimatedVisibility(visible = caption.isNotBlank() || heard.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33000000))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            if (caption.isNotBlank()) {
                Text("“$caption”", color = Palette.Text, fontSize = 11.sp, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            } else {
                Text("You: $heard", color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Warn.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = Palette.Warn, fontSize = 8.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 10.5.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BatteryChip(level: Int, modifier: Modifier) {
    val color = when {
        level <= 15 -> Palette.Stop
        level <= 30 -> Palette.Warn
        else -> Palette.Muted
    }
    Text("🔋 $level %", color = color, fontSize = 9.sp, modifier = modifier)
}
