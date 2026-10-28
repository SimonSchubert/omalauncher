package dev.omalauncher.linux

/** Kernel descriptor rights, including O_PATH, without an API-30 Java dependency. */
internal object PortalFd {
    init { System.loadLibrary("wlbridge") }
    external fun flags(fd: Int): Int
}
