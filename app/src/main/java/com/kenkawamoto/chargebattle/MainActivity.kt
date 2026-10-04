package com.kenkawamoto.chargebattle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.random.Random

/**
 * Phones lie on the table bottom-to-bottom, joined by the cable, with each player sitting at the
 * far end of their phone. The player therefore sees the screen upside down, so the whole UI is
 * rotated 180°: "up" on screen points at the cable and the opponent.
 */
private const val FACE_OFF = true

private val ChargeIn = Color(0xFF3DDC84)
private val ChargeOut = Color(0xFFFF7A3D)
private val Idle = Color(0xFF9AA0A6)

private enum class Flow { NONE, IN, OUT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Battle.start(this)
        setContent { BattleScreen() }
    }
}

@Composable
private fun BattleScreen() {
    val s by Battle.state.collectAsState()
    val flow = when {
        !s.linkUp -> Flow.NONE
        s.charging -> Flow.IN
        else -> Flow.OUT
    }
    val accent by animateColorAsState(
        when (flow) {
            Flow.IN -> ChargeIn
            Flow.OUT -> ChargeOut
            Flow.NONE -> Idle
        },
        label = "accent",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E11))
            .rotate(if (FACE_OFF) 180f else 0f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { if (it.changedToDown()) Battle.tap() }
                    }
                }
            },
    ) {
        // The battery sits at 45% of the height; the stream runs between it and the cable edge (top).
        EnergyStream(flow, abs(s.currentMa), accent, batteryTop = 0.33f, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (s.linkUp) "THEM  ${s.theirRate}" else "",
                color = if (s.leader == Side.THEM) Color.White else Color.White.copy(alpha = 0.45f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 56.dp),
            )
            Spacer(Modifier.weight(1f))
            BatteryGauge(s.batteryLevel, flow, accent, Modifier.size(width = 130.dp, height = 220.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                when (flow) {
                    Flow.NONE -> "Connect the other phone"
                    Flow.IN -> "CHARGING"
                    Flow.OUT -> "DRAINING"
                },
                color = accent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
            )
            if (flow != Flow.NONE) {
                Text("${s.currentMa.signed()} mA", color = accent.copy(alpha = 0.8f), fontSize = 16.sp)
            }
            Spacer(Modifier.weight(1.3f))
            Text(
                if (s.linkUp) "YOU  ${s.myRate}" else "",
                color = if (s.leader == Side.ME) Color.White else Color.White.copy(alpha = 0.45f),
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
            )
            Text("tap anywhere", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            DebugFooter(s)
        }
    }
}

/**
 * Particles travelling between the battery and the cable edge (y = 0). Outflow runs up towards
 * the cable, inflow comes down from it, so on two phones the stream reads as one continuous flow.
 */
@Composable
private fun EnergyStream(flow: Flow, currentMa: Int, color: Color, batteryTop: Float, modifier: Modifier) {
    val particles = remember { List(70) { Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1e9f
                last = now
            }
        }
    }
    Canvas(modifier) {
        val cableX = size.width / 2
        val endY = size.height * batteryTop
        // The cable stub at the port edge.
        drawLine(
            color.copy(alpha = if (flow == Flow.NONE) 0.25f else 0.9f),
            Offset(cableX, 0f), Offset(cableX, 36.dp.toPx()),
            strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round,
        )
        if (flow == Flow.NONE) return@Canvas
        // Stream speed (fraction of the path per second) follows the real current.
        val speed = 0.35f + currentMa.coerceIn(0, 2000) / 2000f * 0.9f
        particles.forEach { p ->
            val progress = (p.phase + time * speed * (0.75f + 0.5f * p.size)) % 1f
            val fromCable = if (flow == Flow.IN) progress else 1f - progress
            val y = fromCable * endY
            // Narrow at the cable, fanning out towards the battery.
            val x = cableX + (p.lane - 0.5f) * size.width * 0.45f * fromCable
            val fade = minOf(1f, progress * 4f, (1f - progress) * 4f)
            drawCircle(color.copy(alpha = 0.85f * fade), radius = (2.5f + 4f * p.size).dp.toPx(), center = Offset(x, y))
        }
    }
}

private class Particle(val lane: Float, val phase: Float, val size: Float)

@Composable
private fun BatteryGauge(level: Int, flow: Flow, color: Color, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val capH = 14.dp.toPx()
            val capW = size.width * 0.38f
            // Terminal cap points at the cable.
            drawRoundRect(
                Color.White.copy(alpha = 0.8f),
                topLeft = Offset((size.width - capW) / 2, 0f),
                size = Size(capW, capH + stroke),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            val bodyTop = capH
            val bodyH = size.height - bodyTop
            drawRoundRect(
                Color.White.copy(alpha = 0.8f),
                topLeft = Offset(stroke / 2, bodyTop + stroke / 2),
                size = Size(size.width - stroke, bodyH - stroke),
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(stroke),
            )
            val inset = stroke * 2
            val innerH = bodyH - inset * 2
            val fillH = innerH * level.coerceIn(0, 100) / 100f
            drawRoundRect(
                color.copy(alpha = if (flow == Flow.OUT) pulse else 1f),
                topLeft = Offset(inset, bodyTop + inset + innerH - fillH),
                size = Size(size.width - inset * 2, fillH),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            if (flow == Flow.IN) drawBolt(Offset(size.width / 2, bodyTop + bodyH / 2), bodyH * 0.45f)
        }
        Text(
            "$level%",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBolt(center: Offset, h: Float) {
    val w = h * 0.55f
    val path = Path().apply {
        moveTo(center.x + w * 0.15f, center.y - h / 2)
        lineTo(center.x - w / 2, center.y + h * 0.08f)
        lineTo(center.x - w * 0.02f, center.y + h * 0.08f)
        lineTo(center.x - w * 0.15f, center.y + h / 2)
        lineTo(center.x + w / 2, center.y - h * 0.08f)
        lineTo(center.x + w * 0.02f, center.y - h * 0.08f)
        close()
    }
    drawPath(path, Color.White.copy(alpha = 0.35f))
}

@Composable
private fun DebugFooter(s: BattleState) {
    val peer = when (s.peerCharging) {
        null -> "?"
        true -> "charging ${s.peerCurrentMa.signed()} mA, ${s.peerBatteryLevel}%"
        false -> "draining ${s.peerCurrentMa.signed()} mA, ${s.peerBatteryLevel}%"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${s.role} · ${s.setup}\nlink ${s.rxPerSec}/s · gap ${s.maxGapMs}ms · drops ${s.linkDrops} · swaps ${s.swaps}\npeer: $peer",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (s.role == Role.REFEREE) {
                TextButton(onClick = Battle::manualSwap) { Text("swap", color = Color.White.copy(alpha = 0.5f)) }
            }
        }
        s.log.lastOrNull()?.let {
            Text(it, color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun Int.signed() = if (this > 0) "+$this" else "$this"
