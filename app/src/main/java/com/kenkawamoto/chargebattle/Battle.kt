package com.kenkawamoto.chargebattle

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Role { STARTING, REFEREE, PLAYER }
enum class Side { ME, THEM, NONE }

data class BattleState(
    val role: Role = Role.STARTING,
    val setup: String = "Starting…",
    val linkUp: Boolean = false,
    val myTaps: Int = 0,
    val myRate: Int = 0,
    val theirRate: Int = 0,
    val leader: Side = Side.NONE,
    val charging: Boolean = false,
    val currentMa: Int = 0,
    val batteryLevel: Int = 0,
    val peerCharging: Boolean? = null,
    val peerCurrentMa: Int = 0,
    val peerBatteryLevel: Int? = null,
    val rxPerSec: Int = 0,
    val maxGapMs: Long = 0,
    val linkDrops: Int = 0,
    val swaps: Int = 0,
    val log: List<String> = emptyList(),
)

/**
 * Tug-of-war tap game. Whoever has tapped more in the last [WINDOW_MS] gets charged.
 *
 * The phone with Shizuku is the referee: it owns the game state, is the USB host, and swaps
 * power roles. The other phone is the player: it sends taps and shows what the referee says.
 * Each phone's "charging / giving" display comes from its own battery, not from messages.
 *
 * Wire protocol (newline-delimited):
 *   player → referee: `T` (one tap), `H <seq>` (heartbeat)
 *   referee → player: `S <refereeRate> <playerRate> <R|P|N leader>` (every tick)
 *   both ways:        `B <plugged 0|1> <battery mA> <battery %>`
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object Battle {
    private const val TAG = "ChargeBattle"
    private const val WINDOW_MS = 2000L
    private const val MIN_SWAP_INTERVAL_MS = 2500L
    private const val TICK_MS = 100L
    private const val GAP_LOG_THRESHOLD_MS = 300L

    val state = MutableStateFlow(BattleState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context
    private lateinit var power: PowerControl
    private var started = false

    @Volatile private var link: Link? = null

    // Referee-only game state.
    private val myTapTimes = ArrayDeque<Long>()
    private val theirTapTimes = ArrayDeque<Long>()
    @Volatile private var refereePowerRole: String? = null
    @Volatile private var lastSwapAt = 0L

    // Link health.
    private val rxTimes = ArrayDeque<Long>()
    private var lastRxAt = 0L

    private fun now() = SystemClock.elapsedRealtime()

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        power = PowerControl(app, ::log)
        scope.launch { chooseRole() }
        scope.launch { batteryLoop() }
    }

    fun tap() {
        state.update { it.copy(myTaps = it.myTaps + 1) }
        when (state.value.role) {
            Role.REFEREE -> synchronized(myTapTimes) { myTapTimes.addLast(now()) }
            Role.PLAYER -> link?.send("T")
            Role.STARTING -> {}
        }
    }

    fun manualSwap() {
        swapTo(if (refereePowerRole == "sink") "source" else "sink")
    }

    private suspend fun chooseRole() {
        power.start()
        val hasShizuku = withTimeoutOrNull(2000) {
            while (!Shizuku.pingBinder()) delay(100)
            true
        } ?: false
        if (hasShizuku) runReferee() else runPlayer()
    }

    private suspend fun runReferee() {
        state.update { it.copy(role = Role.REFEREE) }
        scope.launch {
            power.state.collect { s ->
                val text = when (s) {
                    PowerControl.State.NOT_RUNNING -> "Shizuku not running"
                    PowerControl.State.NO_PERMISSION -> "Grant Shizuku permission"
                    PowerControl.State.BINDING -> "Starting shell service…"
                    PowerControl.State.READY -> "Shizuku ready"
                }
                state.update { it.copy(setup = text) }
            }
        }
        power.state.first { it == PowerControl.State.READY }

        val port = power.readPort()
        if (port == null) {
            log("No USB-C port found in dumpsys usb")
            return
        }
        log("${port.id}: ${port.powerRole}/${port.dataRole} connected=${port.connected}")

        scope.launch { refereeTickLoop() }
        val host = AoaHost(app, ::log)
        connectLoop({ onLine, onClosed ->
            ensureUsbHost()
            host.tryConnect(onLine, onClosed)
        })
    }

    /**
     * The referee must be USB host to drive the accessory link. USB resets (e.g. toggling USB
     * debugging, replugging) fall back to default roles, so re-check whenever the link is down.
     */
    private fun ensureUsbHost() {
        val port = power.readPort() ?: return
        if (!port.connected) return
        refereePowerRole = port.powerRole
        if (port.dataRole != "host") {
            power.setRoles(port.powerRole, "host")
            log("Took USB host role (was ${port.powerRole}/${port.dataRole})")
        }
    }

    private suspend fun runPlayer() {
        state.update { it.copy(role = Role.PLAYER, setup = "No Shizuku: playing as player") }
        scope.launch {
            var seq = 0
            while (true) {
                link?.send("H ${seq++}")
                delay(TICK_MS)
            }
        }
        val accessory = AoaAccessory(app, ::log)
        connectLoop(accessory::tryConnect, accessory::isAttached)
    }

    private suspend fun connectLoop(
        attempt: (onLine: (String) -> Unit, onClosed: () -> Unit) -> Link?,
        stillAttached: () -> Boolean = { true },
    ) {
        while (true) {
            val current = link
            if (current == null) {
                val opened = runCatching { attempt(::onLine, ::onLinkClosed) }
                    .onFailure { log("Connect failed: ${it.message}") }
                    .getOrNull()
                if (opened != null) {
                    synchronized(rxTimes) { rxTimes.clear(); lastRxAt = 0 }
                    link = opened
                    state.update { it.copy(linkUp = true, maxGapMs = 0) }
                    log("Link up")
                }
            } else if (!stillAttached()) {
                current.close()
            }
            delay(1000)
        }
    }

    private fun onLinkClosed() {
        link = null
        state.update { it.copy(linkUp = false, peerCharging = null, linkDrops = it.linkDrops + 1) }
        log("Link down")
    }

    private fun onLine(line: String) {
        recordRx()
        val parts = line.split(' ')
        when (parts[0]) {
            "T" -> synchronized(theirTapTimes) { theirTapTimes.addLast(now()) }
            "B" -> state.update {
                it.copy(
                    peerCharging = parts[1] == "1",
                    peerCurrentMa = parts[2].toInt(),
                    peerBatteryLevel = parts.getOrNull(3)?.toInt(),
                )
            }
            "S" -> state.update {
                it.copy(
                    theirRate = parts[1].toInt(),
                    myRate = parts[2].toInt(),
                    leader = when (parts[3]) {
                        "P" -> Side.ME
                        "R" -> Side.THEM
                        else -> Side.NONE
                    },
                )
            }
        }
    }

    private fun recordRx() {
        val t = now()
        synchronized(rxTimes) {
            if (lastRxAt != 0L) {
                val gap = t - lastRxAt
                if (gap > state.value.maxGapMs) state.update { it.copy(maxGapMs = gap) }
                if (gap > GAP_LOG_THRESHOLD_MS) log("Link gap ${gap}ms")
            }
            lastRxAt = t
            rxTimes.addLast(t)
            while (rxTimes.first() < t - 1000) rxTimes.removeFirst()
            state.update { it.copy(rxPerSec = rxTimes.size) }
        }
    }

    private suspend fun refereeTickLoop() {
        while (true) {
            val t = now()
            val mine = countRecent(myTapTimes, t)
            val theirs = countRecent(theirTapTimes, t)
            val leader = when {
                mine > theirs -> Side.ME
                theirs > mine -> Side.THEM
                else -> Side.NONE
            }
            state.update { it.copy(myRate = mine, theirRate = theirs, leader = leader) }
            val code = when (leader) {
                Side.ME -> "R"
                Side.THEM -> "P"
                Side.NONE -> "N"
            }
            link?.send("S $mine $theirs $code")

            if (link != null && leader != Side.NONE) {
                val want = if (leader == Side.ME) "sink" else "source"
                if (want != refereePowerRole && t - lastSwapAt >= MIN_SWAP_INTERVAL_MS) swapTo(want)
            }
            delay(TICK_MS)
        }
    }

    private fun countRecent(times: ArrayDeque<Long>, t: Long): Int = synchronized(times) {
        while (times.isNotEmpty() && times.first() < t - WINDOW_MS) times.removeFirst()
        times.size
    }

    private fun swapTo(powerRole: String) {
        if (state.value.role != Role.REFEREE || power.state.value != PowerControl.State.READY) return
        lastSwapAt = now()
        refereePowerRole = powerRole
        scope.launch(Dispatchers.IO) {
            val t0 = now()
            runCatching { power.setRoles(powerRole, "host") }
                .onSuccess {
                    log("Swap: referee → $powerRole (${now() - t0}ms)")
                    state.update { it.copy(swaps = it.swaps + 1) }
                }
                .onFailure { log("Swap failed: ${it.message}") }
        }
    }

    private suspend fun batteryLoop() {
        val battery = app.getSystemService(BatteryManager::class.java)
        var ticks = 0
        while (true) {
            val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Positive = into the battery, in µA on Pixels.
            val ma = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
            state.update { it.copy(charging = plugged, currentMa = ma, batteryLevel = level) }
            if (ticks++ % 2 == 0) link?.send("B ${if (plugged) 1 else 0} $ma $level")
            delay(250)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(message: String) {
        Log.i(TAG, message)
        val line = "${timeFormat.format(Date())} $message"
        state.update { it.copy(log = (it.log + line).takeLast(40)) }
    }
}
