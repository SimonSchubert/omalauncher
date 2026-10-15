package dev.omalauncher

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.writeAtomically
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The launcher's own data that Linux also sees. It lives outside the rootfs,
 * so it exists without Linux and survives installing or removing it; proot
 * binds it to the paths Omarchy and agents expect (see [dev.omalauncher.linux.Proot]).
 */
object LauncherData {
    /** Omarchy's release: themes, wallpapers, templates. The guest sees ~/.local/share/omarchy. */
    fun omarchyDir(context: Context) = File(context.filesDir, "omarchy")

    /** The user's own Omarchy themes. The guest sees ~/.config/omarchy/themes, where omarchy-theme-install puts them. */
    fun userThemesDir(context: Context) = File(context.filesDir, "themes")

    /** The user's extra backgrounds, per theme. The guest sees ~/.config/omarchy/backgrounds. */
    fun userBackgroundsDir(context: Context) = File(context.filesDir, "backgrounds")

    /** Widget files. The guest sees ~/.config/omalauncher/widgets. */
    fun widgetsDir(context: Context) = File(context.filesDir, "widgets")

    /** style.toml and its defaults; see [dev.omalauncher.theme.StyleStore]. The guest sees ~/.config/omalauncher/style. */
    fun styleDir(context: Context) = File(context.filesDir, "style")

    /** Manifest of the launcher-side [dev.omalauncher.upgrade.Provisioner]; the guest's is managed.json. */
    const val LAUNCHER_MANIFEST = "launcher-managed.json"
    const val GUEST_MANIFEST = "managed.json"

    private const val OLD_OMARCHY = "arch/root/.local/share/omarchy"
    private const val OLD_WIDGETS = "arch/root/.config/omalauncher/widgets"

    /**
     * Launchers before Linux was optional kept this data inside the rootfs.
     * Moves it out once, and its records in the guest manifest along with it,
     * so seeded widgets stay ours and keep getting updates. Idempotent.
     */
    fun migrate(context: Context) {
        val files = context.filesDir
        move(File(files, OLD_OMARCHY), omarchyDir(context))
        if (move(File(files, OLD_WIDGETS), widgetsDir(context))) moveManifestEntries(files)
    }

    /** Renames [from] to [to] if [from] is a real directory and [to] doesn't exist yet. */
    private fun move(
        from: File,
        to: File,
    ): Boolean {
        if (to.exists() || !from.isDirectory || Files.isSymbolicLink(from.toPath())) return false
        val ok = from.renameTo(to)
        Log.i(TAG, "moved $from -> $to: $ok")
        return ok
    }

    private fun moveManifestEntries(files: File) {
        val guest = File(files, GUEST_MANIFEST)
        val launcher = File(files, LAUNCHER_MANIFEST)
        runCatching {
            val all = Versioned.read(guest.takeIf { it.exists() }?.readText(), MANIFEST_VERSION) as? JSONObject ?: return
            val moved = JSONObject()
            for (key in all.keys().asSequence().toList()) {
                if (!key.startsWith("$OLD_WIDGETS/")) continue
                moved.put("widgets/" + key.removePrefix("$OLD_WIDGETS/"), all.getString(key))
                all.remove(key)
            }
            if (moved.length() == 0) return
            writeAtomically(launcher, Versioned.write(MANIFEST_VERSION, moved).toByteArray())
            writeAtomically(guest, Versioned.write(MANIFEST_VERSION, all).toByteArray())
        }.onFailure { Log.w(TAG, "can't move the widget records", it) }
    }

    /** [dev.omalauncher.upgrade.ManagedFiles]' manifest version. */
    private const val MANIFEST_VERSION = 1

    /**
     * Deletes [dir] and everything in it without following symlinks: the
     * rootfs holds absolute guest links that resolve elsewhere on the host.
     * Directories get write permission first, since the rootfs has read-only
     * ones (the tarball's modes). Throws if anything is left.
     */
    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    runCatching { Os.chmod(d.toString(), Os.lstat(d.toString()).st_mode and 0x1ff or 0x1c0) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                /** The walker lists a directory before [preVisitDirectory]: an unreadable one (mode 000) fails here first. */
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    val path = file.toString()
                    val mode = runCatching { Os.lstat(path).st_mode }.getOrNull() ?: throw exc
                    if (!OsConstants.S_ISDIR(mode) || mode and 0x1c0 == 0x1c0) throw exc
                    Os.chmod(path, mode and 0x1ff or 0x1c0)
                    deleteTree(file.toFile())
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    d: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private const val TAG = "launcher-data"
}
