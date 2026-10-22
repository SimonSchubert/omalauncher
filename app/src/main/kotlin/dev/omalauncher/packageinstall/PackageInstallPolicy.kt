package dev.omalauncher.packageinstall

/**
 * The two prompts involved in an agent-requested install.
 *
 * YOLO is deliberately limited to the host bridge: it skips Omalauncher's
 * approval screen and asks PackageInstaller not to require user action. Android
 * can still require its own confirmation based on device policy. An update of
 * Omalauncher itself remains explicitly approved.
 */
internal data class PackageInstallApprovalPolicy(
    val yolo: Boolean,
) {
    val showLauncherApproval: Boolean
        get() = !yolo

    val requestAndroidUserAction: Boolean
        get() = !yolo
}

/** The launcher cannot silently replace itself, even when unattended installs are enabled. */
internal fun requiresExplicitApproval(
    packageName: String,
    launcherPackageName: String,
) = packageName == launcherPackageName
