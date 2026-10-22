package dev.omalauncher.packageinstall

/**
 * The two prompts involved in an agent-requested install.
 *
 * YOLO is deliberately limited to the host bridge: it skips Omalauncher's
 * approval screen and asks PackageInstaller not to require user action. Android
 * can still require its own confirmation based on device policy.
 */
internal data class PackageInstallApprovalPolicy(
    val yolo: Boolean,
) {
    val showLauncherApproval: Boolean
        get() = !yolo

    val requestAndroidUserAction: Boolean
        get() = !yolo
}
