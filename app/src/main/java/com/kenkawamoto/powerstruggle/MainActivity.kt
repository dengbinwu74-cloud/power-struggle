package com.kenkawamoto.powerstruggle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Two phones: they lie on the table bottom-to-bottom, joined by the cable, with each player sitting
 * at the far end of their phone. The player therefore sees the screen upside down, so the UI is
 * rotated 180°: "up" on screen points at the cable and the opponent.
 *
 * One phone: the phone lies between the two players and the screen is split. Each half is drawn
 * like a two-phone screen whose "cable edge" is the middle line.
 */
private const val FACE_OFF = true

private val ChargeIn = Color(0xFF3DDC84)
private val ChargeOut = Color(0xFFFF7A3D)
private val Idle = Color(0xFF9AA0A6)

private enum class Flow { NONE, IN, OUT }

/** What one player's view shows. [rope] is from this player's side: +1 at their battery. */
private class PlayerSide(
    val flow: Flow,
    val rope: Float,
    val batteryLevel: Int?,
    val currentMa: Int?,
    val label: String? = null,
)

private class Ripple(val position: Offset, val startedAt: Float)

private class Particle(val lane: Float, val phase: Float, val size: Float)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Keep the status bar: its charging icon is proof that power really moved.
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Battle.start(this)
        setContent { BattleScreen() }
    }
}

@Composable
private fun BattleScreen() {
    val s by Battle.state.collectAsState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val myFlow = when {
        s.mode == Mode.NONE -> Flow.NONE
        s.charging -> Flow.IN
        else -> Flow.OUT
    }
    val rope = if (s.mode == Mode.NONE) 0f else s.rope

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0E11))) {
        if (s.mode == Mode.ONE_PHONE) {
            val otherFlow = when (myFlow) {
                Flow.IN -> Flow.OUT
                Flow.OUT -> Flow.IN
                Flow.NONE -> Flow.NONE
            }
            Column(Modifier.fillMaxSize()) {
                // This phone's player sits at the top; the status bar is along their near edge.
                PlayerView(
                    PlayerSide(myFlow, rope, s.batteryLevel, s.currentMa, label = "THIS PHONE"),
                    onTap = Battle::tap,
                    streamFraction = 0.3f,
                    compact = true,
                    bottomInset = statusBarHeight,
                    modifier = Modifier.weight(1f).rotate(180f),
                )
                // The other phone hangs off the cable at the bottom, in front of its player.
                PlayerView(
                    PlayerSide(otherFlow, -rope, batteryLevel = null, currentMa = null, label = "OTHER PHONE"),
                    onTap = Battle::tapOther,
                    streamFraction = 0.3f,
                    compact = true,
                    bottomInset = 0.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            PlayerView(
                PlayerSide(myFlow, rope, s.batteryLevel, s.currentMa),
                onTap = Battle::tap,
                streamFraction = 0.42f,
                compact = false,
                bottomInset = statusBarHeight,
                modifier = Modifier.fillMaxSize().rotate(if (FACE_OFF) 180f else 0f),
                footer = { DebugFooter(s, Modifier.padding(top = 16.dp)) },
            )
        }
    }
}

/** One player's view: energy stream from the cable edge (top) to their battery, and the knot. */
@Composable
private fun PlayerView(
    side: PlayerSide,
    onTap: () -> Unit,
    streamFraction: Float,
    compact: Boolean,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val flow = side.flow
    val accent by animateColorAsState(
        when (flow) {
            Flow.IN -> ChargeIn
            Flow.OUT -> ChargeOut
            Flow.NONE -> Idle
        },
        label = "accent",
    )
    val startNanos = remember { System.nanoTime() }
    val now = remember { { (System.nanoTime() - startNanos) / 1e9f } }
    val ripples = remember { mutableStateListOf<Ripple>() }
    var lastTapAt by remember { mutableFloatStateOf(-10f) }

    BoxWithConstraints(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach {
                        if (it.changedToDown()) {
                            onTap()
                            lastTapAt = now()
                            ripples.add(Ripple(it.position, lastTapAt))
                            if (ripples.size > 12) ripples.removeAt(0)
                        }
                    }
                }
            }
        },
    ) {
        val streamEnd = maxHeight * streamFraction
        val batteryHeight = if (compact) minOf(150.dp, maxHeight * 0.34f) else 200.dp
        EnergyCanvas(
            flow = flow,
            rope = side.rope,
            color = accent,
            streamFraction = streamFraction,
            // Split screen shows the knot in the other half already.
            showOffscreenKnot = !compact,
            ripples = ripples,
            lastTapAt = lastTapAt,
            now = now,
            modifier = Modifier.fillMaxSize(),
        )
        BatteryGauge(
            side.batteryLevel, flow, accent,
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = streamEnd)
                .size(width = batteryHeight * 0.6f, height = batteryHeight),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = streamEnd + batteryHeight + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val caption = listOfNotNull(side.label, side.batteryLevel?.let { "$it%" }).joinToString("  ·  ")
            if (caption.isNotEmpty()) {
                Text(caption, color = Color.White, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                when (flow) {
                    Flow.NONE -> "Connect the other phone"
                    Flow.IN -> "CHARGING"
                    Flow.OUT -> "DRAINING"
                },
                color = accent,
                fontSize = if (compact) 24.sp else 30.sp,
                fontWeight = FontWeight.Black,
            )
            if (flow != Flow.NONE && side.currentMa != null) {
                Text("${side.currentMa.signed()} mA", color = accent.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp + bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (flow) {
                    Flow.NONE -> ""
                    Flow.IN -> "keep tapping to hold it"
                    Flow.OUT -> "TAP TO STEAL CHARGE!"
                },
                color = Color.White.copy(alpha = if (flow == Flow.OUT) 0.9f else 0.4f),
                fontSize = if (flow == Flow.OUT) 22.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            )
            footer?.invoke()
        }
    }
}

/**
 * The energy stream runs from the cable edge (y = 0) to the battery terminal. Particles flow in
 * the real direction of charge; the knot rides on the stream at the tug position. A knot on the
 * other phone lies off the top edge, which lines up with where it is drawn on that phone.
 */
@Composable
private fun EnergyCanvas(
    flow: Flow,
    rope: Float,
    color: Color,
    streamFraction: Float,
    showOffscreenKnot: Boolean,
    ripples: List<Ripple>,
    lastTapAt: Float,
    now: () -> Float,
    modifier: Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    val particles = remember { List(80) { Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    // [displayed knot position, time of last frame]; smooths the 20 Hz updates from the referee.
    val smooth = remember { floatArrayOf(0f, 0f) }

    Canvas(modifier) {
        frame // Redraw every frame.
        val t = now()
        val dt = (t - smooth[1]).coerceIn(0f, 0.1f)
        smooth[1] = t
        smooth[0] += (rope - smooth[0]) * min(1f, dt * 12f)
        val knot = smooth[0]

        val cx = size.width / 2
        val length = size.height * streamFraction
        val intensity = abs(knot).coerceIn(0.15f, 1f)

        // Beam.
        val beamAlpha = if (flow == Flow.NONE) 0.15f else 0.25f + 0.45f * intensity
        drawLine(color.copy(alpha = beamAlpha * 0.25f), Offset(cx, 0f), Offset(cx, length), strokeWidth = (14 + 22 * intensity).dp.toPx())
        drawLine(color.copy(alpha = beamAlpha), Offset(cx, 0f), Offset(cx, length), strokeWidth = 3.dp.toPx())
        drawLine(color.copy(alpha = 0.9f), Offset(cx, 0f), Offset(cx, 32.dp.toPx()), strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round)

        // Swap line: the knot has to pass this for power to come to you.
        val swapY = Battle.SWAP_THRESHOLD * length
        listOf(-1f, 1f).forEach { side ->
            drawLine(
                Color.White.copy(alpha = 0.35f),
                Offset(cx + side * 26.dp.toPx(), swapY), Offset(cx + side * 46.dp.toPx(), swapY),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
        }

        // Particles.
        if (flow != Flow.NONE) {
            // Constant speed so the direction stays readable; only the density follows intensity.
            val speed = 0.5f
            val count = (20 + 60 * intensity).toInt()
            particles.take(count).forEach { p ->
                val progress = (p.phase + t * speed * (0.75f + 0.5f * p.size)) % 1f
                val fromCable = if (flow == Flow.IN) progress else 1f - progress
                val wiggle = sin(t * 3f + p.phase * 20f) * 5.dp.toPx()
                val x = cx + (p.lane - 0.5f) * size.width * (0.08f + 0.35f * fromCable) + wiggle
                val pos = Offset(x, fromCable * length)
                val fade = minOf(1f, progress * 5f, (1f - progress) * 5f)
                val r = (2.5f + 4f * p.size).dp.toPx()
                drawCircle(color.copy(alpha = 0.18f * fade), r * 2.8f, pos)
                drawCircle(color.copy(alpha = 0.9f * fade), r, pos)
            }
        }

        // Knot.
        if (knot >= 0f) {
            val pulse = 1f + 0.35f * kotlin.math.exp(-(t - lastTapAt) * 10f)
            val pos = Offset(cx, knot * length)
            drawCircle(Color.White.copy(alpha = 0.15f), 34.dp.toPx() * pulse, pos)
            drawCircle(Color.White, 15.dp.toPx() * pulse, pos, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), pos)
        } else if (showOffscreenKnot) {
            // Knot is on their phone: glow at the cable edge, brighter the further away it is.
            drawCircle(Color.White.copy(alpha = 0.08f + 0.2f * -knot), (24 + 50 * -knot).dp.toPx(), Offset(cx, 0f))
        }

        // Tap ripples.
        ripples.forEach { ripple ->
            val age = (t - ripple.startedAt) / 0.45f
            if (age in 0f..1f) {
                drawCircle(
                    Color.White.copy(alpha = 0.5f * (1f - age)),
                    (12 + 70 * age).dp.toPx(),
                    ripple.position,
                    style = Stroke(3.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun BatteryGauge(level: Int?, flow: Flow, color: Color, modifier: Modifier) {
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
            // Unknown level (the other phone without the app): show it half full.
            val fillH = innerH * (level ?: 50).coerceIn(0, 100) / 100f
            drawRoundRect(
                color.copy(alpha = if (flow == Flow.OUT) pulse else 1f),
                topLeft = Offset(inset, bodyTop + inset + innerH - fillH),
                size = Size(size.width - inset * 2, fillH),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            if (flow == Flow.IN) {
                drawBolt(Offset(size.width / 2, bodyTop + bodyH / 2), bodyH * 0.6f * (0.92f + 0.08f * pulse))
            }
        }
    }
}

private fun DrawScope.drawBolt(center: Offset, h: Float) {
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
    drawPath(path, Color(0xFF0D0E11), style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
    drawPath(path, Color.White)
}

@Composable
private fun DebugFooter(s: BattleState, modifier: Modifier = Modifier) {
    val peer = when (s.peerCharging) {
        null -> "?"
        true -> "charging ${s.peerCurrentMa.signed()} mA, ${s.peerBatteryLevel}%"
        false -> "draining ${s.peerCurrentMa.signed()} mA, ${s.peerBatteryLevel}%"
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${s.role} · ${s.setup} · rope ${"%.2f".format(s.rope)}\n" +
                    "link ${s.rxPerSec}/s · gap ${s.maxGapMs}ms · drops ${s.linkDrops} · swaps ${s.swaps}\n" +
                    "peer: $peer",
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
