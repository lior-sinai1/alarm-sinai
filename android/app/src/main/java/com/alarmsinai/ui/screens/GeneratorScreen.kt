package com.alarmsinai.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
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

        ModeCard(gen)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "מצב תפעולי",
                style = MaterialTheme.typography.titleSmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            StatusRow("מתח רשת", state = boolState(gen?.mains))
            StatusRow("מתח גנרטור", state = StateInfo.Unavailable)
            StatusRow("גנרטור מונע", state = StateInfo.Unavailable)
            StatusRow("תקלה בגנרטור", state = boolState(gen?.fault, invert = true))
            StatusRow(
                "לחץ שמן מנוע",
                state = boolState(gen?.oilPressure, invert = true),
                icon = { tint -> OilPressureIcon(tint) }
            )
            StatusRow(
                "חום מנוע",
                state = boolState(gen?.engineTemp, invert = true),
                icon = { tint -> EngineTempIcon(tint) }
            )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("מצב פעולה", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(12.dp))
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
    Canvas(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer { rotationZ = angleDegrees }
    ) {
        val knobColor = Color(0xFF1C1C1C)
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Round base of the knob
        drawCircle(color = knobColor, radius = radius * 0.62f, center = center)

        // The pointing "blade" — extends up from the base towards the active position
        val bladeWidth = radius * 1.05f
        val bladeHeight = radius * 1.35f
        drawRoundRect(
            color = knobColor,
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

private sealed class StateInfo {
    data class On(val label: String, val color: Color) : StateInfo()
    object Unavailable : StateInfo()
}

// `invert` = true means "value == true" is a bad/alert condition (e.g. a fault bit)
// rather than a normal "yes" condition (e.g. mains voltage present).
private fun boolState(value: Boolean?, invert: Boolean = false): StateInfo {
    if (value == null) return StateInfo.On("--", AlarmGray)
    return when {
        value && invert  -> StateInfo.On("פעיל", AlarmRed)
        value            -> StateInfo.On("כן", AlarmGreen)
        invert           -> StateInfo.On("תקין", AlarmGray)
        else             -> StateInfo.On("לא", AlarmGray)
    }
}

@Composable
private fun StatusRow(
    name: String,
    state: StateInfo,
    icon: (@Composable (tint: Color) -> Unit)? = null
) {
    val iconTint = when (state) {
        is StateInfo.On -> state.color
        is StateInfo.Unavailable -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(
                alpha = if (state is StateInfo.Unavailable) 0.5f else 1f
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                icon?.invoke(iconTint)
                Text(name, color = Color.White)
            }
            when (state) {
                is StateInfo.Unavailable -> Text("לא זמין", color = Color.Gray, fontSize = 13.sp)
                is StateInfo.On -> Text(state.label, color = state.color, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Dashboard-style telltale icons: oil-can-with-drip (oil pressure) and
// thermometer-over-water (engine temperature), matching the reference
// warning-light icons. Drawn as vector paths so they tint with the row state.
@Composable
private fun OilPressureIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(26.dp)) {
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
            quadraticTo(dropCx + w * 0.09f, h * 0.62f, dropCx, h * 0.78f)
            quadraticTo(dropCx - w * 0.09f, h * 0.62f, dropCx, dropTopY)
            close()
        }
        drawPath(drop, color = tint)
    }
}

@Composable
private fun EngineTempIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(26.dp)) {
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
        val wave: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = { yFrac ->
            val amp = h * 0.045f
            val yBase = h * yFrac
            val step = w / 4f
            val path = Path().apply { moveTo(0f, yBase) }
            var x = 0f
            var up = true
            while (x < w) {
                val nextX = (x + step).coerceAtMost(w)
                path.quadraticTo(x + step / 2f, yBase + (if (up) -amp else amp), nextX, yBase)
                up = !up
                x = nextX
            }
            drawPath(path, color = tint, style = waveStroke)
        }
        wave(0.82f)
        wave(0.95f)
    }
}
