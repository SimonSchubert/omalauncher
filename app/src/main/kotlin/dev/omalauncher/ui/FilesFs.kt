package dev.omalauncher.ui

import android.net.Uri
import dev.omalauncher.PhoneFolders
import dev.omalauncher.PhoneStorage
import dev.omalauncher.linux.GuestEntry
import dev.omalauncher.linux.GuestFs
import java.io.File
import java.io.IOException

/**
 * What Files shows: the Linux file system, and under [PHONE] the phone folders the user granted ([PhoneFolders]),
 * which Linux has no path to. A path there is [PHONE] and then the folder's name and on: "phone:/Camera/2026". Copies
 * and moves go between the two either way.
 */
internal class FilesFs(
    private val guest: GuestFs,
    private val phone: PhoneFolders,
) {
    fun isPhone(path: String) = path.startsWith(PHONE)

    /** The path within the phone folders, "Camera/2026"; empty for [PHONE] itself. */
    private fun inPhone(path: String) = guest.normalize(path.removePrefix(PHONE)).removePrefix("/")

    fun normalize(path: String) = if (isPhone(path)) PHONE + "/" + inPhone(path) else guest.normalize(path)

    /** Where a folder's row leads: through links, for Linux. */
    fun resolve(path: String) = if (isPhone(path)) normalize(path) else guest.resolve(path)

    fun isDir(path: String) = if (isPhone(path)) inPhone(path).isEmpty() || doc(path).isDir else guest.isDir(path)

    /** A phone file's content uri, for Android's apps; null for a Linux one. */
    fun uri(path: String): Uri? = if (isPhone(path) && inPhone(path).isNotEmpty()) doc(path).uri else null

    /** Whether [path] is one of the phone folders themselves, which Files lists but doesn't delete, rename or move. */
    fun isPhoneFolder(path: String) = isPhone(path) && inPhone(path).let { it.isNotEmpty() && '/' !in it }

    /** The Android file of a Linux path; see [GuestFs.host]. */
    fun host(path: String): File = guest.host(path)

    fun list(
        dir: String,
        showHidden: Boolean,
    ): List<GuestEntry> {
        if (!isPhone(dir)) return guest.list(dir, showHidden)
        val docs = if (inPhone(dir).isEmpty()) phone.folders().map { it.root } else phone.list(doc(dir))
        return docs
            .filter { showHidden || !it.name.startsWith('.') }
            .map { GuestEntry(it.name, "$PHONE/${it.path}", it.isDir, isLink = false, size = it.size, modified = it.modified, children = null) }
    }

    fun mkdir(
        dir: String,
        name: String,
    ): String = if (isPhone(dir)) phonePath(phone.mkdir(writable(dir), name)) else guest.mkdir(dir, name)

    fun createFile(
        dir: String,
        name: String,
    ): String = if (isPhone(dir)) phonePath(phone.createFile(writable(dir), name)) else guest.createFile(dir, name)

    fun rename(
        path: String,
        name: String,
    ): String {
        if (!isPhone(path)) return guest.rename(path, name)
        if (isPhoneFolder(path)) throw IOException(NOT_THE_FOLDER)
        val doc = doc(path)
        val dir = doc.path.substringBeforeLast('/')
        if (name != doc.name && phone.child(doc(PHONE + "/" + dir), name) != null) throw IOException("$name already exists")
        return phonePath(phone.rename(doc, name))
    }

    fun delete(path: String) {
        if (!isPhone(path)) return guest.delete(path)
        if (isPhoneFolder(path)) throw IOException(NOT_THE_FOLDER)
        phone.delete(doc(path))
    }

    /** Copies or moves [paths] into [dir], from and to Linux or the phone folders; "name (copy)" on clashes. */
    fun paste(
        paths: List<String>,
        dir: String,
        move: Boolean,
    ) {
        if (paths.none(::isPhone) && !isPhone(dir)) return guest.paste(paths, dir, move)
        for (path in paths) {
            if (move && isPhoneFolder(path)) throw IOException(NOT_THE_FOLDER)
            val from = normalize(resolve(path))
            val to = normalize(resolve(dir))
            if (to == from || to.startsWith("$from/")) throw IOException("can't put a folder inside itself")
            if (move && to == normalize(parentOf(from))) continue // already here
            when {
                isPhone(path) && isPhone(dir) -> {
                    val target = writable(dir)
                    copyWithin(doc(path), target, freeName(phone.list(target).map { it.name }, doc(path).name))
                }
                isPhone(path) -> {
                    val src = doc(path)
                    phone.copyIn(src, File(guest.host(dir), guest.freeName(guest.resolve(dir), src.name)))
                }
                else -> {
                    // Through a link, in Linux: a phone folder can't hold one. Under its own name, not its target's.
                    val target = writable(dir)
                    phone.copyOut(guest.host(path), target, freeName(phone.list(target).map { it.name }, path.substringAfterLast('/')))
                }
            }
            if (move) delete(path)
        }
    }

    /** One phone file or folder into another phone folder, each file by way of a temporary copy of the launcher's. */
    private fun copyWithin(
        src: PhoneFolders.Doc,
        dir: PhoneFolders.Doc,
        name: String,
    ) {
        if (src.isDir) {
            val made = phone.mkdir(dir, name)
            for (c in phone.list(src)) copyWithin(c, made, c.name)
        } else {
            val tmp = File.createTempFile("copy", null)
            try {
                phone.copyIn(src, tmp)
                phone.copyOut(tmp, dir, name)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }

    private fun doc(path: String): PhoneFolders.Doc = phone.find(inPhone(path)) ?: throw IOException("no ${inPhone(path)}")

    /** A phone folder to make things in: not the list of folders itself. */
    private fun writable(dir: String): PhoneFolders.Doc {
        if (inPhone(dir).isEmpty()) throw IOException(NOT_HERE)
        return doc(dir).also { if (!it.isDir) throw IOException("${it.name} isn't a folder") }
    }

    private fun phonePath(doc: PhoneFolders.Doc) = "$PHONE/${doc.path}"

    companion object {
        const val PHONE = "phone:"
        private const val NOT_THE_FOLDER = "a phone folder itself is added and removed in settings (permissions)"
        private const val NOT_HERE = "add phone folders in settings (permissions); files go in them"

        /**
         * Where Files shows [path] if it's in the phone's storage at /sdcard, inside a folder the user granted: under
         * [PHONE], with everyone's files, as /sdcard has only Linux's own. The innermost of [grants], which are
         * (where it is in the storage, the folder's name). Null when it's not in one.
         */
        fun viaGrant(
            path: String,
            grants: List<Pair<String, String>>,
        ): String? {
            val inStorage = path.takeIf { it.startsWith(PhoneStorage.GUEST + "/") }?.removePrefix(PhoneStorage.GUEST + "/") ?: return null
            val (at, name) =
                grants
                    .filter { (at, _) -> at.isNotEmpty() && (inStorage == at || inStorage.startsWith("$at/")) }
                    .maxByOrNull { it.first.length } ?: return null
            return ("$PHONE/$name/" + inStorage.removePrefix(at).trim('/')).trimEnd('/')
        }

        /** [name], or "name (copy)" and on when [taken] has it, as [GuestFs.paste] names them. */
        fun freeName(
            taken: Collection<String>,
            name: String,
        ): String {
            if (name !in taken) return name
            val stem = name.substringBeforeLast('.').takeIf { '.' in name && !name.startsWith('.') } ?: name
            val ext = if (stem == name) "" else "." + name.substringAfterLast('.')
            return generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) "$stem (copy)$ext" else "$stem (copy $n)$ext" }
                .first { it !in taken }
        }
    }
}
