package dev.omadroid

/** The optional Linux environment's session: whether it is installed and whether it is running. */
sealed interface LinuxSessionState {
    /** Linux was never installed, or was removed. */
    data object NotInstalled : LinuxSessionState

    /** Installed on disk, but not running. */
    data object Stopped : LinuxSessionState

    /** Starting: the Wayland server and bridges are coming up. */
    data object Starting : LinuxSessionState

    /** Running: guest commands work. */
    data object Running : LinuxSessionState

    /** Stopping: guest processes are being shut down. */
    data object Stopping : LinuxSessionState

    /** The last attempt to start failed; [message] says why. */
    data class Failed(val message: String) : LinuxSessionState
}
