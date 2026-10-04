package com.kenkawamoto.powerstruggle

import kotlin.system.exitProcess

/**
 * Shizuku user service. Shizuku runs this class in a separate process with shell uid,
 * which is allowed to call `dumpsys usb set-port-roles`.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(cmd: Array<String>): String {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
