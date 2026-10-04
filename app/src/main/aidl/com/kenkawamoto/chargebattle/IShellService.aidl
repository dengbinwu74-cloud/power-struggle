package com.kenkawamoto.chargebattle;

// Runs inside a Shizuku user service process with shell uid.
interface IShellService {
    // Transaction code reserved by Shizuku for destroying the service.
    void destroy() = 16777114;

    // Runs a command and returns its combined stdout/stderr.
    String run(in String[] cmd) = 1;
}
