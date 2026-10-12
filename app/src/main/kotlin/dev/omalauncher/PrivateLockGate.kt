package dev.omalauncher

/** A lock request hides apps until Android confirms the profile entered quiet mode. */
internal data class PrivateLockGate(private val pending: Set<Long> = emptySet()) {
    fun request(profiles: Set<Long>) = copy(pending = pending + profiles)

    fun failed(profile: Long) = copy(pending = pending - profile)

    fun observedLocked(profiles: Set<Long>) = copy(pending = pending - profiles)

    fun blocks(profile: Long?) = profile != null && profile in pending
}
