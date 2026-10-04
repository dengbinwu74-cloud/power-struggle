package com.kenkawamoto.chargebattle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Battle.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BattleScreen()
            }
        }
    }
}

@Composable
private fun BattleScreen() {
    val s by Battle.state.collectAsState()
    val background = when {
        !s.linkUp -> Color(0xFF202124)
        s.charging -> Color(0xFF0B5D1E)
        else -> Color(0xFF7A2E0E)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${s.role} · ${s.setup}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(
            text = when {
                !s.linkUp -> "Waiting for opponent…"
                s.charging -> "⚡ Charging  ${s.currentMa.signed()} mA"
                else -> "Giving charge  ${s.currentMa.signed()} mA"
            },
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You ${s.myRate} · Them ${s.theirRate}  (taps / 2 s)  " + when (s.leader) {
                Side.ME -> "— you're winning"
                Side.THEM -> "— they're winning"
                Side.NONE -> ""
            },
            color = Color.White,
            fontSize = 16.sp,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { if (it.changedToDown()) Battle.tap() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("TAP!\n${s.myTaps}", color = Color.White, fontSize = 48.sp, lineHeight = 56.sp, textAlign = TextAlign.Center)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.role == Role.REFEREE) Button(onClick = Battle::manualSwap) { Text("Swap power") }
            val peer = when (s.peerCharging) {
                null -> "?"
                true -> "charging ${s.peerCurrentMa.signed()} mA"
                false -> "giving ${s.peerCurrentMa.signed()} mA"
            }
            Text(
                "link ${s.rxPerSec}/s · max gap ${s.maxGapMs} ms · drops ${s.linkDrops} · swaps ${s.swaps}\npeer: $peer",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }
        Column(Modifier.height(120.dp)) {
            s.log.takeLast(6).forEach {
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun Int.signed() = if (this > 0) "+$this" else "$this"
