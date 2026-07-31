package com.alarmsinai.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmsinai.data.model.GeneratorStatus
import com.alarmsinai.ui.theme.AlarmGray
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.AlarmViewModel
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GeneratorScreen(vm: AlarmViewModel) {
    val status by vm.status.collectAsState()
    val error by vm.connectionError.collectAsState()
    val connected = !error && status?.connected == true
    val gen = status?.generator

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("גנרטור", style = MaterialTheme.typography.titleLarge)
        TopStatusCard(gen, connected)
        ModeCard(gen)
    }
}

private val AlarmMaintenanceBlue = Color(0xFF2979FF)

private val oilFault: (GeneratorStatus?) -> Boolean = { it != null && it.oilPressure }
private val tempFault: (GeneratorStatus?) -> Boolean = { it != null && it.engineTemp }

// Priority order: active faults override the mode/power text.
// Returns (top line, bottom line or null, color).
private fun statusLines(gen: GeneratorStatus?, connected: Boolean): Triple<String, String?, Color> = when {
    !connected || gen == null -> Triple("מתחבר...", null, AlarmGray)
    oilFault(gen) -> Triple("לחץ שמן נמוך", null, AlarmRed)
    tempFault(gen) -> Triple("חום מנוע", null, AlarmRed)
    gen.disabled -> Triple("מושבט", null, AlarmGray)
    gen.maintenance -> Triple("טיפול", null, AlarmMaintenanceBlue)
    gen.manual && gen.manualRunning -> Triple("מערכת פועלת במצב ידני", null, AlarmRed)
    gen.manual && gen.manualCounter > 0 -> Triple("מצב ידני", "${gen.manualCounter}", AlarmRed)
    gen.manual -> Triple("מצב ידני", null, AlarmRed)
    gen.automatic && gen.mains -> Triple("מתח רשת", "מערכת בהמתנה", AlarmGreen)
    gen.automatic && !gen.mains -> Triple("רשת מנותקת", "גנרטור חירום", AlarmRed)
    else -> Triple("לא ידוע", null, AlarmGray)
}

// Engine hours only accrue while the generator is actually producing power —
// running on the grid (mains) does not turn the engine.
private fun engineRunning(gen: GeneratorStatus?): Boolean =
    gen != null && ((gen.automatic && !gen.mains) || (gen.manual && gen.manualRunning))

@Composable
private fun TopStatusCard(gen: GeneratorStatus?, connected: Boolean) {
    val (statusText, statusText2, statusColor) = statusLines(gen, connected)
    val running = engineRunning(gen)
    val infinite = rememberInfiniteTransition(label = "hourglassBlink")
    val hourglassAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 0.15f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "hourglassAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        // Forced LTR so the icon column always pins to the literal left edge,
        // regardless of the app's RTL (Hebrew) layout direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 34.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OilPressureIcon(
                            tint = if (oilFault(gen)) AlarmRed else Color.Gray,
                            modifier = Modifier.size(26.dp)
                        )
                        EngineTempIcon(
                            tint = if (tempFault(gen)) AlarmRed else Color.Gray,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            statusText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            textAlign = TextAlign.Center
                        )
                        if (statusText2 != null) {
                            Text(
                                statusText2,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                // Engine hour-meter: whole hours before the point, tenths of an
                // hour (6-minute ticks) after it — pinned to the top-left corner.
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.HourglassEmpty,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).graphicsLayer { alpha = hourglassAlpha }
                    )
                    Text(
                        "${gen?.engineHoursWhole ?: 0}.${gen?.engineHoursTenths ?: 0}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// The 4-position selector switch on the generator controller, in physical order.
// Angle is measured clockwise from "up" (0°), matching the knob's resting orientation.
private data class SwitchPosition(val label: String, val angle: Float, val color: Color)

private val SWITCH_POSITIONS = listOf(
    SwitchPosition("מנוטרל", 67.5f, AlarmGray),
    SwitchPosition("אוטו", -22.5f, AlarmGreen),
    SwitchPosition("ידני", 22.5f, AlarmRed),
    SwitchPosition("טיפול", -67.5f, AlarmMaintenanceBlue),
)

@Composable
private fun ModeCard(gen: GeneratorStatus?) {
    val activeIndex = when {
        gen == null -> -1
        gen.disabled -> 0
        gen.automatic -> 1
        gen.manual -> 2
        gen.maintenance -> 3
        else -> -1
    }
    val targetAngle = if (activeIndex >= 0) SWITCH_POSITIONS[activeIndex].angle else 0f
    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = tween(400),
        label = "knobAngle"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Forced LTR so the label offsets (mirrored by Compose under RTL)
            // stay aligned with the knob's rotationZ (never mirrored).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier.size(220.dp, 190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val labelRadius = 92.dp
                    SWITCH_POSITIONS.forEachIndexed { index, pos ->
                        val rad = Math.toRadians(pos.angle.toDouble())
                        val x = labelRadius * sin(rad).toFloat()
                        val y = -labelRadius * cos(rad).toFloat()
                        val active = index == activeIndex
                        Text(
                            pos.label,
                            modifier = Modifier
                                .offset(x = x, y = y)
                                .widthIn(max = 64.dp),
                            color = if (active) pos.color else Color.Gray,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (active) 15.sp else 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    SelectorKnob(angleDegrees = animatedAngle, activeIndex = activeIndex)
                }
            }
        }
    }
}

// Illuminated-lens colors per mode. Automatic stays lit solid green;
// disabled is solid gray; every other mode blinks in its own color (manual = red, maintenance = blue).
private fun lensColorFor(activeIndex: Int): Pair<Color, Boolean> = when (activeIndex) {
    0 -> AlarmGray to false                // disabled — gray, solid
    1 -> AlarmGreen to false              // automatic — green, solid
    2 -> AlarmRed to true                 // manual — red, blinking
    3 -> AlarmMaintenanceBlue to true     // maintenance — blue, blinking
    else -> Color.Gray to false
}

@Composable
private fun SelectorKnob(angleDegrees: Float, activeIndex: Int) {
    val (lensColor, blinking) = lensColorFor(activeIndex)
    val infinite = rememberInfiniteTransition(label = "knobBlink")
    val blinkAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (blinking) 0.2f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "blinkAlpha"
    )

    // Static dark bezel (does not rotate) — mimics the plastic mounting ring
    // of a physical panel-mount selector switch.
    Box(
        modifier = Modifier
            .size(92.dp)
            .shadow(elevation = 8.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF3C3C3C), Color(0xFF1A1A1A))
                )
            )
            .border(width = 2.dp, color = Color(0xFF0A0A0A), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer { rotationZ = angleDegrees }
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Paddle-shaped lever — narrow at the hub, flaring wider towards the
            // rounded tip, matching the physical selector-switch lever shape.
            val hubWidth = radius * 0.55f
            val tipWidth = radius * 0.95f
            val length = radius * 1.35f
            val hubY = center.y + hubWidth * 0.35f

            val paddle = Path().apply {
                moveTo(center.x - hubWidth / 2f, hubY)
                lineTo(center.x - tipWidth / 2f, center.y - length)
                quadraticBezierTo(
                    center.x, center.y - length - tipWidth * 0.32f,
                    center.x + tipWidth / 2f, center.y - length
                )
                lineTo(center.x + hubWidth / 2f, hubY)
                quadraticBezierTo(
                    center.x, hubY + hubWidth * 0.7f,
                    center.x - hubWidth / 2f, hubY
                )
                close()
            }

            // Translucent illuminated lens — a soft glow behind a semi-transparent fill
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lensColor.copy(alpha = 0.55f * blinkAlpha),
                        lensColor.copy(alpha = 0f)
                    ),
                    center = Offset(center.x, center.y - length * 0.55f),
                    radius = radius * 1.3f
                ),
                radius = radius * 1.3f,
                center = Offset(center.x, center.y - length * 0.55f)
            )
            drawPath(paddle, color = lensColor.copy(alpha = 0.6f * blinkAlpha))
            drawPath(
                paddle,
                color = Color.Black.copy(alpha = 0.55f),
                style = Stroke(width = radius * 0.06f)
            )

            // Soft highlight streak down the paddle, like light through translucent plastic
            drawLine(
                color = Color.White.copy(alpha = 0.5f * blinkAlpha),
                start = Offset(center.x, center.y - length * 0.15f),
                end = Offset(center.x, center.y - length * 0.85f),
                strokeWidth = tipWidth * 0.16f,
                cap = StrokeCap.Round
            )
        }
    }
}

// Dashboard-style telltale icons: oil-can-with-drip (oil pressure) and
// thermometer-over-water (engine temperature), matching the reference
// warning-light icons. Drawn as vector paths so they tint with the current fault state.
@Composable
private fun OilPressureIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.10f
        val strokeStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Can body — hexagonal outline with an angled facet at the top-left
        // (the handle cut) and a flat top where the filler cap sits.
        val body = Path().apply {
            moveTo(w * 0.10f, h * 0.85f)
            lineTo(w * 0.10f, h * 0.55f)
            lineTo(w * 0.24f, h * 0.40f)
            lineTo(w * 0.58f, h * 0.40f)
            lineTo(w * 0.70f, h * 0.55f)
            lineTo(w * 0.58f, h * 0.85f)
            close()
        }
        drawPath(body, color = tint, style = strokeStyle)

        // Spout — rises from the can's right shoulder out towards the drip
        drawLine(
            color = tint,
            start = Offset(w * 0.66f, h * 0.50f),
            end = Offset(w * 0.90f, h * 0.30f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Filler cap — a "T" sitting centered on the flat top of the can
        drawLine(
            color = tint,
            start = Offset(w * 0.46f, h * 0.40f),
            end = Offset(w * 0.46f, h * 0.24f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.36f, h * 0.24f),
            end = Offset(w * 0.56f, h * 0.24f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Drip below the spout tip
        val dropCx = w * 0.90f
        val dropTopY = h * 0.32f
        val drop = Path().apply {
            moveTo(dropCx, dropTopY)
            quadraticBezierTo(dropCx + w * 0.09f, h * 0.62f, dropCx, h * 0.78f)
            quadraticBezierTo(dropCx - w * 0.09f, h * 0.62f, dropCx, dropTopY)
            close()
        }
        drawPath(drop, color = tint)
    }
}

@Composable
private fun EngineTempIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stemStroke = w * 0.16f

        // Thermometer stem + bulb
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * 0.08f),
            end = Offset(w * 0.42f, h * 0.55f),
            strokeWidth = stemStroke,
            cap = StrokeCap.Round
        )
        drawCircle(color = tint, radius = w * 0.15f, center = Offset(w * 0.42f, h * 0.62f))

        // Gauge ticks
        val tickStroke = stemStroke * 0.5f
        listOf(0.16f, 0.28f, 0.40f).forEach { fy ->
            drawLine(
                color = tint,
                start = Offset(w * 0.58f, h * fy),
                end = Offset(w * 0.76f, h * fy),
                strokeWidth = tickStroke,
                cap = StrokeCap.Round
            )
        }

        // Wavy water lines below
        val waveStroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        val wave: DrawScope.(Float) -> Unit = { yFrac ->
            val amp = h * 0.045f
            val yBase = h * yFrac
            val step = w / 4f
            val path = Path().apply { moveTo(0f, yBase) }
            var x = 0f
            var up = true
            while (x < w) {
                val nextX = (x + step).coerceAtMost(w)
                path.quadraticBezierTo(x + step / 2f, yBase + (if (up) -amp else amp), nextX, yBase)
                up = !up
                x = nextX
            }
            drawPath(path, color = tint, style = waveStroke)
        }
        wave(0.82f)
        wave(0.95f)
    }
}
