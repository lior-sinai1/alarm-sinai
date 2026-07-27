package com.alarmsinai.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmsinai.data.model.GeneratorStatus
import com.alarmsinai.ui.theme.AlarmGray
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmOrange
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.AlarmViewModel
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GeneratorScreen(vm: AlarmViewModel) {
    val status by vm.status.collectAsState()
    val gen = status?.generator

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("גנרטור", style = MaterialTheme.typography.titleLarge)
        FaultIconsRow(gen)
        ModeCard(gen)
    }
}

private val oilFault: (GeneratorStatus?) -> Boolean = { it != null && it.fault && !it.oilPressure }
private val tempFault: (GeneratorStatus?) -> Boolean = { it != null && it.fault && it.engineTemp }

// Priority order: active faults override the mode/power text.
private fun statusLine(gen: GeneratorStatus?): Pair<String, Color> = when {
    gen == null -> "מתחבר..." to AlarmGray
    oilFault(gen) -> "לחץ שמן נמוך" to AlarmRed
    tempFault(gen) -> "חום מנוע" to AlarmRed
    gen.disabled -> "גנרטור מושבת" to AlarmGray
    gen.maintenance -> "טיפול" to AlarmOrange
    gen.manual -> "גנרטור פועל ידני" to AlarmOrange
    gen.automatic && gen.mains -> "מתח רשת" to AlarmGreen
    gen.automatic && !gen.mains -> "מתח גנרטור" to AlarmGreen
    else -> "לא ידוע" to AlarmGray
}

@Composable
private fun FaultIconsRow(gen: GeneratorStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OilPressureIcon(tint = if (oilFault(gen)) AlarmRed else Color.Gray, modifier = Modifier.size(40.dp))
            EngineTempIcon(tint = if (tempFault(gen)) AlarmRed else Color.Gray, modifier = Modifier.size(40.dp))
        }
    }
}

// The 4-position selector switch on the generator controller, in physical order.
// Angle is measured clockwise from "up" (0°), matching the knob's resting orientation.
private data class SwitchPosition(val label: String, val angle: Float, val color: Color)

private val SWITCH_POSITIONS = listOf(
    SwitchPosition("מנוטרל", -67.5f, AlarmGray),
    SwitchPosition("אוטומטי", -22.5f, AlarmGreen),
    SwitchPosition("ידני", 22.5f, AlarmOrange),
    SwitchPosition("טיפול", 67.5f, AlarmOrange),
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
    val (statusText, statusColor) = statusLine(gen)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                statusText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
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
                SelectorKnob(angleDegrees = animatedAngle)
            }
        }
    }
}

@Composable
private fun SelectorKnob(angleDegrees: Float) {
    // Static ring plate (does not rotate) — gives the white frame around the knob.
    Box(
        modifier = Modifier
            .size(92.dp)
            .shadow(elevation = 8.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF2A2A2A))
            .border(width = 3.dp, color = Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer { rotationZ = angleDegrees }
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            // Embossed radial gradient — lighter towards the top-left, darker towards
            // the bottom-right, to read as a raised, physical knob face.
            val knobBrush = Brush.radialGradient(
                colors = listOf(Color(0xFF4A4A4A), Color(0xFF0E0E0E)),
                center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
                radius = radius * 1.6f
            )

            // Round base of the knob
            drawCircle(brush = knobBrush, radius = radius * 0.62f, center = center)

            // The pointing "blade" — extends up from the base towards the active position
            val bladeWidth = radius * 1.05f
            val bladeHeight = radius * 1.35f
            drawRoundRect(
                brush = knobBrush,
                topLeft = Offset(center.x - bladeWidth / 2f, center.y - bladeHeight),
                size = Size(bladeWidth, bladeHeight * 0.85f),
                cornerRadius = CornerRadius(bladeWidth / 2f, bladeWidth / 2f)
            )

            // White indicator stripe down the blade
            drawLine(
                color = Color.White,
                start = Offset(center.x, center.y - radius * 0.15f),
                end = Offset(center.x, center.y - bladeHeight * 0.78f),
                strokeWidth = bladeWidth * 0.24f,
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

        // Can body
        val body = Path().apply {
            moveTo(w * 0.10f, h * 0.42f)
            lineTo(w * 0.10f, h * 0.82f)
            lineTo(w * 0.62f, h * 0.82f)
            lineTo(w * 0.78f, h * 0.42f)
            close()
        }
        drawPath(body, color = tint, style = strokeStyle)

        // Spout
        drawLine(
            color = tint,
            start = Offset(w * 0.62f, h * 0.42f),
            end = Offset(w * 0.90f, h * 0.28f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Cap / handle notch
        val cap = Path().apply {
            moveTo(w * 0.22f, h * 0.42f)
            lineTo(w * 0.22f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.42f)
        }
        drawPath(cap, color = tint, style = strokeStyle)
        drawLine(
            color = tint,
            start = Offset(w * 0.31f, h * 0.24f),
            end = Offset(w * 0.31f, h * 0.12f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Drip below the spout tip
        val dropCx = w * 0.92f
        val dropTopY = h * 0.44f
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
