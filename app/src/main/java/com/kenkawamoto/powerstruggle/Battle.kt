package com.kenkawamoto.powerstruggle

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
import java.util.concurrent.atomic.AtomicInteger

enum class Role { STARTING, REFEREE, PLAYER }

/**
 * TWO_PHONES: the other phone runs the app and shows its own side.
 * ONE_PHONE: the other device is connected but not running the app (not installed yet, or an
 * iPad/iPhone, which can't talk the accessory protocol), so this phone shows both players.
 */
enum class Mode { NONE, ONE_PHONE, TWO_PHONES }

data class BattleState(
    val role: Role = Role.STARTING,
    val setup: String = "Starting…",
    val linkUp: Boolean = false,
    val mode: Mode = Mode.NONE,
    /** Product name of the USB partner, as seen by the referee. */
    val partnerName: String? = null,
    val myTaps: Int = 0,
    /** Knot position from this phone's view: +1 at my battery, 0 at the cable, -1 at theirs. */
    val rope: Float = 0f,
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
 * Tug-of-war tap game. Every tap pulls a knot along the energy stream towards the tapper; the
 * knot slowly springs back towards the cable. Once the knot is [SWAP_THRESHOLD] past the cable on
 * your side, power swaps so that you are charged.
 *
 * The phone with Shizuku is the referee: it owns the game state, is the USB host, and swaps
 * power roles. The other phone is the player: it sends taps and shows what the referee says.
 * Each phone's "charging / giving" display comes from its own battery, not from messages.
 *
 * Wire protocol (newline-delimited):
 *   player → referee: `T` (one tap), `H <seq>` (heartbeat)
 *   referee → player: `S <rope>` (every tick, from the player's view)
 *   both ways:        `B <plugged 0|1> <battery mA> <battery %>`
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object Battle {
    private const val TAG = "PowerStruggle"
    private const val TICK_MS = 50L
    private const val TAP_PULL = 0.07f
    private const val SPRING_BACK_PER_SEC = 0.35f
    const val SWAP_THRESHOLD = 0.15f
    /** The peer counts as running the app while we've heard from it this recently. */
    private const val PEER_TIMEOUT_MS = 2000L
    private const val MIN_SWAP_INTERVAL_MS = 1000L
    private const val GAP_LOG_THRESHOLD_MS = 300L

    val state = MutableStateFlow(BattleState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context
    private lateinit var power: PowerControl
    private var started = false

    @Volatile private var link: Link? = null
    @Volatile private var linkOpenedAt = 0L
    @Volatile private var peerActive = false
    @Volatile private var aoaHost: AoaHost? = null

    // Referee-only game state.
    private val myPendingTaps = AtomicInteger()
    private val theirPendingTaps = AtomicInteger()
    @Volatile private var refereePowerRole: String? = null
    @Volatile private var lastSwapAt = 0L

    // Link health.
    private val rxTimes = ArrayDeque<Long>()
    @Volatile private var lastRxAt = 0L

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
            Role.REFEREE -> myPendingTaps.incrementAndGet()
            Role.PLAYER -> link?.send("T")
            Role.STARTING -> {}
        }
    }

    /** One-phone mode: a tap from the other player's half of the screen. */
    fun tapOther() {
        if (state.value.mode == Mode.ONE_PHONE) theirPendingTaps.incrementAndGet()
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
        aoaHost = host
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
                sendToPeer("H ${seq++}")
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
                    linkOpenedAt = now()
                    link = opened
                    state.update {
                        it.copy(linkUp = true, maxGapMs = 0, mode = if (it.role == Role.PLAYER) Mode.TWO_PHONES else it.mode)
                    }
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
        state.update {
            it.copy(
                linkUp = false,
                peerCharging = null,
                linkDrops = it.linkDrops + 1,
                mode = if (it.role == Role.PLAYER) Mode.NONE else it.mode,
            )
        }
        log("Link down")
    }

    private fun onLine(line: String) {
        recordRx()
        val parts = line.split(' ')
        when (parts[0]) {
            "T" -> theirPendingTaps.incrementAndGet()
            "B" -> state.update {
                it.copy(
                    peerCharging = parts[1] == "1",
                    peerCurrentMa = parts[2].toInt(),
                    peerBatteryLevel = parts.getOrNull(3)?.toInt(),
                )
            }
            "S" -> state.update { it.copy(rope = parts[1].toFloat()) }
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
        var rope = 0f // Referee's view: positive = towards the referee's battery.
        var last = now()
        while (true) {
            val t = now()
            val dt = (t - last) / 1000f
            last = t
            val pull = myPendingTaps.getAndSet(0) - theirPendingTaps.getAndSet(0)
            val currentLink = link
            peerActive = currentLink != null && t - lastRxAt < PEER_TIMEOUT_MS
            val mode = when {
                peerActive -> Mode.TWO_PHONES
                // The accessory link is open but silent: the other phone isn't running the app.
                currentLink != null && t - linkOpenedAt > PEER_TIMEOUT_MS -> Mode.ONE_PHONE
                currentLink == null && aoaHost?.partnerWithoutAoa == true -> Mode.ONE_PHONE
                else -> Mode.NONE
            }
            rope = if (mode == Mode.NONE) {
                0f
            } else {
                (rope + pull * TAP_PULL) * (1f - SPRING_BACK_PER_SEC * dt)
            }.coerceIn(-1f, 1f)
            state.update { it.copy(rope = rope, mode = mode, partnerName = aoaHost?.partnerName) }
            sendToPeer("S ${"%.3f".format(Locale.US, -rope)}")

            if (mode != Mode.NONE && t - lastSwapAt >= MIN_SWAP_INTERVAL_MS) {
                when {
                    rope > SWAP_THRESHOLD && refereePowerRole != "sink" -> swapTo("sink")
                    rope < -SWAP_THRESHOLD && refereePowerRole != "source" -> swapTo("source")
                }
            }
            delay(TICK_MS)
        }
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

    /**
     * The referee only writes once the peer app is talking: with nobody reading the accessory end,
     * bulk writes would block and pile up.
     */
    private fun sendToPeer(line: String) {
        if (state.value.role == Role.PLAYER || peerActive) link?.send(line)
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
            if (ticks++ % 2 == 0) sendToPeer("B ${if (plugged) 1 else 0} $ma $level")
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
