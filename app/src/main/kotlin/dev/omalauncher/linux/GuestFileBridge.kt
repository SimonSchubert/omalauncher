package dev.omalauncher.linux

import java.io.File

/** Resolve again when answering: the guest may have changed a link while its picker was open. */
internal fun guestReplyFile(fs: GuestFs, path: String): File? = runCatching {
    val prefix = Proot.GUEST_RUNTIME_DIR + "/"
    if (!path.startsWith(prefix) || path.split('/').any { it == ".." }) return null
    val resolved = fs.resolve(path)
    if (!resolved.startsWith(prefix)) return null
    fs.host(resolved)
}.getOrNull()
