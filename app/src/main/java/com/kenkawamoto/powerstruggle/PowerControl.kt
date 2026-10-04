package com.kenkawamoto.powerstruggle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

data class UsbPort(val id: String, val powerRole: String, val dataRole: String, val connected: Boolean)

/** Switches USB-C power/data roles through a Shizuku-hosted shell process. */
class PowerControl(context: Context, private val log: (String) -> Unit) {
    enum class State { NOT_RUNNING, NO_PERMISSION, BINDING, READY }

    val state = MutableStateFlow(State.NOT_RUNNING)

    @Volatile private var shell: IShellService? = null
    private var portId = "port0"

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            shell = IShellService.Stub.asInterface(binder)
            state.value = State.READY
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shell = null
            state.value = State.BINDING
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky { onShizukuAvailable() }
        Shizuku.addBinderDeadListener {
            shell = null
            state.value = State.NOT_RUNNING
            log("Shizuku stopped")
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bind() else log("Shizuku permission denied")
        }
    }

    private fun onShizukuAvailable() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bind()
        } else {
            state.value = State.NO_PERMISSION
            Shizuku.requestPermission(1)
        }
    }

    private fun bind() {
        state.value = State.BINDING
        Shizuku.bindUserService(serviceArgs, connection)
    }

    private fun run(vararg cmd: String): String =
        checkNotNull(shell) { "Shizuku shell service not ready" }.run(arrayOf(*cmd))

    /** Reads the first USB-C port's state from `dumpsys usb`. */
    fun readPort(): UsbPort? {
        val ports = run("dumpsys", "usb").substringAfter("usb_ports={", "")
        if (ports.isEmpty()) return null
        fun field(key: String) = Regex("""\b$key=(\S+)""").find(ports)?.groupValues?.get(1)
        val port = UsbPort(
            id = field("id") ?: return null,
            powerRole = field("power_role") ?: "unknown",
            dataRole = field("data_role") ?: "unknown",
            connected = field("connected") == "true",
        )
        portId = port.id
        return port
    }

    /** powerRole: source|sink, dataRole: host|device. Triggers a USB PD role swap. */
    fun setRoles(powerRole: String, dataRole: String): String =
        run("dumpsys", "usb", "set-port-roles", portId, powerRole, dataRole)
}
