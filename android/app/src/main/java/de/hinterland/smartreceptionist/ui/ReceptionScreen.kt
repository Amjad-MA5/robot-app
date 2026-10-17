package de.hinterland.smartreceptionist.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import de.hinterland.smartreceptionist.R
import de.hinterland.smartreceptionist.flow.InputKind
import de.hinterland.smartreceptionist.flow.Phase
import de.hinterland.smartreceptionist.flow.PhotoMode
import de.hinterland.smartreceptionist.flow.UiOption
import de.hinterland.smartreceptionist.flow.UiState
import de.hinterland.smartreceptionist.net.Employee
import de.hinterland.smartreceptionist.ui.theme.Palette
import de.hinterland.smartreceptionist.voice.Lines

/** Die Oberfläche ist für 548 × 308 dp (Kopf-Display des Nova) entworfen und wird proportional skaliert. */
private const val DESIGN_WIDTH_DP = 548f

@Composable
fun ScaledToRobotScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = (maxWidth.value / DESIGN_WIDTH_DP).coerceIn(0.85f, 2.4f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            content()
        }
    }
}

interface ReceptionActions {
    fun onScreenTap()
    fun onOption(id: String)
    fun onSubmitText(text: String)
    fun onPickEmployee(employee: Employee)
    fun onCancel()
    fun onStop()
    fun onAdminRequested()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReceptionScreen(state: UiState, lines: Lines, actions: ReceptionActions, cameraFrame: Bitmap? = null) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.phase == Phase.IDLE,
            ) { actions.onScreenTap() },
    ) {
        // Hintergrund wie in den Hinterland-Folien: großes „H“ blass am rechten Rand, kleines Logo unten rechts
        Image(
            painter = painterResource(R.drawable.bg_h_mark),
            contentDescription = null,
            alpha = 0.16f,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(1.05f).offset(x = 60.dp),
        )
        Image(
            painter = painterResource(R.drawable.logo_hinterland),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp).height(24.dp),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            // ---------- links: Gesicht + Untertitel
            Column(
                Modifier.weight(0.42f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    // Badge photo: the visitor sees themselves (mirrored) instead of the robot face
                    state.photoMode == PhotoMode.LIVE ->
                        CameraView(cameraFrame, state.mirrorPreview, state.countdown, Modifier.weight(1f, fill = false))
                    state.photoMode == PhotoMode.REVIEW && state.photo != null ->
                        PhotoView(state.photo, Modifier.weight(1f, fill = false))
                    else -> RobotFace(state.mood, Modifier.fillMaxWidth().aspectRatio(1f).weight(1f, fill = false))
                }
                if (state.caption.isNotBlank() && state.phase != Phase.IDLE) {
                    Text(
                        state.caption,
                        color = Palette.Muted,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // ---------- rechts: Gesprächs-Panel
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = state.phase,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "panel",
                ) { phase ->
                    when (phase) {
                        Phase.IDLE -> IdlePanel(state, lines)
                        Phase.ESCORT, Phase.RETURNING -> DrivingPanel(state, lines, actions)
                        Phase.CONVERSATION -> ConversationPanel(state, lines, actions)
                    }
                }
            }
        }

        // Unsichtbarer Admin-Zugang: lange auf die linke obere Ecke drücken
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(56.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (state.phase == Phase.IDLE) actions.onScreenTap() },
                    onLongClick = { actions.onAdminRequested() },
                ),
        )

        // Status oben rechts
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(state.backendOk == true, if (state.backendOk == null) Palette.Muted else null)
            StatusDot(state.robotOk, null)
            if (state.showCancel) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Palette.Panel)
                        .clickable { actions.onCancel() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Palette.Text, fontSize = 12.sp) }
            }
        }

        state.banner?.let { banner ->
            Text(
                banner,
                color = Palette.AccentDark,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Warn)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean, override: Color?) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(override ?: if (ok) Palette.Ok else Palette.Error),
    )
}

@Composable
private fun IdlePanel(state: UiState, lines: Lines) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(state.title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text(state.subtitle, color = Palette.Muted, fontSize = 13.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.Accent)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(lines.tapToStart, color = Palette.AccentDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.heard.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            HeardChip(state.heard)
        }
    }
}

@Composable
private fun DrivingPanel(state: UiState, lines: Lines, actions: ReceptionActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(state.title, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        if (state.subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.subtitle, color = Palette.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        if (state.showStop) {
            Button(
                onClick = { actions.onStop() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Error, contentColor = Color.White),
                shape = RoundedCornerShape(50),
            ) { Text("■  " + lines.stopButton, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationPanel(state: UiState, lines: Lines, actions: ReceptionActions) {
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.Center) {
        Text(
            state.title,
            color = Palette.Text,
            fontSize = 19.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(state.subtitle, color = Palette.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        if (state.heard.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            HeardChip(state.heard)
        }

        state.badgePreview?.let { jpeg ->
            Spacer(Modifier.height(8.dp))
            JpegImage(
                jpeg,
                contentDescription = "Visitor badge",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1011f / 638f)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }

        state.summary?.let { s ->
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.PanelStrong)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SummaryRow(lines.summaryVisitor, s.visitor)
                SummaryRow(lines.summaryEmployee, s.employee)
                SummaryRow(lines.summaryWhen, s.whenText)
            }
        }

        state.input?.let { input ->
            Spacer(Modifier.height(8.dp))
            TextEntry(input.hint, input.kind, lines) { actions.onSubmitText(it) }
        }

        if (state.options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.options.forEach { OptionButton(it) { actions.onOption(it.id) } }
            }
        }

        if (state.employees.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.employees, key = { it.id }) { e -> EmployeeCard(e) { actions.onPickEmployee(e) } }
            }
        }
    }
}

/** Live camera image with an oval as a guide for the head position and a 3 - 2 - 1 countdown. */
@Composable
private fun CameraView(frame: Bitmap?, mirror: Boolean, countdown: Int?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirror) -1f else 1f },
            )
        } else {
        }
        // Oval: where the face should be
        Canvas(Modifier.fillMaxSize()) {
            val ovalW = size.width * 0.62f
            val ovalH = size.height * 0.58f
            drawOval(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset((size.width - ovalW) / 2, size.height * 0.12f),
                size = Size(ovalW, ovalH),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }
        when {
            countdown == 0 -> Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.85f)))
            countdown != null -> Text(
                countdown.toString(),
                color = Color.White,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 22.dp),
            )
        }
    }
}

/** The photo for review (not mirrored - this is how it appears on the badge). */
@Composable
private fun PhotoView(jpeg: ByteArray, modifier: Modifier = Modifier) {
    JpegImage(
        jpeg,
        contentDescription = "Your photo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun JpegImage(
    jpeg: ByteArray,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = contentDescription, contentScale = contentScale, modifier = modifier)
    }
}

@Composable
private fun HeardChip(text: String) {
    Text(
        "„$text“",
        color = Palette.Accent,
        fontSize = 11.sp,
        fontStyle = FontStyle.Italic,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Panel)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row {
        Text(label, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(value, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OptionButton(option: UiOption, onClick: () -> Unit) {
    if (option.primary) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentDark),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.heightIn(min = 34.dp),
        ) { Text(option.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
            modifier = Modifier.heightIn(min = 34.dp),
        ) { Text(option.label, fontSize = 12.sp) }
    }
}

@Composable
private fun EmployeeCard(employee: Employee, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(employee.name, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOf(employee.department, employee.jobTitle).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, color = Palette.Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextEntry(hint: String, kind: InputKind, lines: Lines, onSubmit: (String) -> Unit) {
    var text by rememberSaveable(kind) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(hint, fontSize = 12.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Palette.Text),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSubmit(text) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Muted,
                cursorColor = Palette.Accent,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
        )
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = { if (text.isNotBlank()) onSubmit(text) },
            enabled = text.isNotBlank(),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentDark),
        ) { Text(lines.next, fontSize = 12.sp) }
    }
}
