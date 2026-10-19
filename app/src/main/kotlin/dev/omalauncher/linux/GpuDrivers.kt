package dev.omalauncher.linux

import dev.omalauncher.readUpTo
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The pinned Vulkan drivers [Gpu] can install. Detection and the guest
 * scripts live here, with no Android types, so they can be tested on the JVM.
 *
 * Mali keeps the PanVK-kbase blob. Adreno 6xx and newer get Turnip built for
 * KGSL, which is what Android exposes at /dev/kgsl-3d0; Arch's own Mesa talks
 * to DRM and would not see that node. Older Adreno GPUs are not offered:
 * Turnip cannot pass its probe there, and the install would still download.
 */
internal enum class GpuKind { MALI, TURNIP }

/** Where the .so comes from. The two shapes do not share a download path. */
internal sealed interface DriverPayload {
    /** The URL is the library itself. */
    data class RawLibrary(val sha256: String) : DriverPayload

    /** The URL is an Arch package; [member] is the library inside it. */
    data class ArchPackage(
        val packageSha256: String,
        val member: String,
        val librarySha256: String,
    ) : DriverPayload
}

internal data class GpuDriver(
    val kind: GpuKind,
    /** What vulkaninfo must print before the driver is trusted. */
    val probeId: String,
    /** Short name for the settings row and the probe's failure line. Vendor names, so the row matches the README. */
    val label: String,
    val packages: List<String>,
    val url: String,
    val payload: DriverPayload,
    val driverPath: String,
    /** Under /usr/local, which pacman does not own. The loader still finds it while the file exists. */
    val icdPath: String,
    val apiVersion: String,
    /**
     * How OpenGL finds this GPU: zink, through the patched libEGL under
     * /usr/local/omalauncher-mesa. Stock Mesa clears Zink when Wayland DRM
     * init fails, then the shm buffer stays black; that happens on every GPU
     * here, Mali included. The override keeps Zink set. The two paths select
     * the patched libEGL and its matching zink, not Arch's.
     */
    val openGlEnv: List<String> = listOf(
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        "GALLIUM_DRIVER=zink",
        "LIBGL_DRIVERS_PATH=/usr/local/omalauncher-mesa/lib/dri",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/usr/local/share/glvnd/egl_vendor.d/50_mesa.json",
    ),
) {
    /**
     * Vulkan through this ICD, presented as wl_shm; OpenGL is zink on top of it.
     * One app can still opt out with `GALLIUM_DRIVER=llvmpipe app`.
     */
    val env: List<String> =
        listOf(
            "VK_ICD_FILENAMES=$icdPath",
            "VK_DRIVER_FILES=$icdPath",
            "MESA_VK_WSI_DEBUG=sw",
        ) + openGlEnv

    val librarySha256: String
        get() = when (val payload = payload) {
            is DriverPayload.RawLibrary -> payload.sha256
            is DriverPayload.ArchPackage -> payload.librarySha256
        }
}

/** Mesa's CPU Vulkan driver, from Arch's vulkan-swrast. */
internal const val LAVAPIPE_ICD = "/usr/share/vulkan/icd.d/lvp_icd.json"

/**
 * Software rendering. VK_ICD_FILENAMES and VK_DRIVER_FILES name only lavapipe,
 * so the loader does not scan the default ICD directories and cannot pick up a
 * GPU driver that was left behind, while Vulkan apps still run on the CPU when
 * vulkan-swrast is installed.
 */
internal val SOFTWARE_ENV = listOf(
    "LIBGL_ALWAYS_SOFTWARE=1",
    "VK_ICD_FILENAMES=$LAVAPIPE_ICD",
    "VK_DRIVER_FILES=$LAVAPIPE_ICD",
)

/**
 * Mali when /dev/mali0 exists, else Turnip when /dev/kgsl-3d0 exists and
 * [kgslModel] is an Adreno 6xx or newer. A phone has one of these. Mali wins
 * if a build exposes both, so a Mali phone keeps the driver it has always had.
 * An unreadable Adreno model is not offered.
 */
internal fun gpuKind(
    hasMali: Boolean,
    hasKgsl: Boolean,
    kgslModel: String? = null,
): GpuKind? = when {
    hasMali -> GpuKind.MALI
    hasKgsl && turnipSupports(kgslModel) -> GpuKind.TURNIP
    else -> null
}

/** The three-digit Adreno model in a kgsl `gpu_model` string, or null when it is not there. */
internal fun adrenoModelNumber(model: String?): Int? {
    if (model.isNullOrBlank()) return null
    val named = Regex("(?i)adreno\\D*(\\d{3})").find(model)
    val token = named ?: Regex("(?<!\\d)(\\d{3})(?!\\d)").find(model.trim())
    return token?.groupValues?.get(1)?.toIntOrNull()
}

/** Turnip in this pin speaks Adreno 6xx and newer. */
internal fun turnipSupports(model: String?): Boolean {
    val number = adrenoModelNumber(model) ?: return false
    return number >= 600
}

internal object GpuDrivers {
    private val basePackages = listOf("mesa", "vulkan-icd-loader", "vulkan-tools", "mesa-utils")

    /** Removed on the way out. A previous install wrote Mali's manifest on pacman's path. */
    const val LEGACY_MALI_ICD = "/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json"

    /**
     * Deletes [legacy], the host file of [LEGACY_MALI_ICD], if it is our manifest for [driver]:
     * a regular file naming its library. Mesa's own Panfrost manifest has that name too.
     */
    fun retireLegacyManifest(
        driver: GpuDriver,
        legacy: File,
    ) {
        if (driver.kind != GpuKind.MALI) return
        val path = legacy.toPath()
        val head = runCatching {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
            Files.newInputStream(path).use { it.readUpTo(4096).decodeToString() }
        }.getOrNull()
        if (head?.contains(driver.driverPath) == true) Files.deleteIfExists(path)
    }

    /**
     * Patched Mesa 26.2.4 (scripts/mesa/0001-egl-wayland-keep-zink-on-shm.patch and 0002-zink-acquire-swapchain-resolve-before-use.patch).
     * The tar is staged into the guest from the APK before [installScript] runs.
     * Not on the default linker path, so software mode keeps Arch's Mesa.
     */
    const val MESA_ZINK_BUNDLE = "/usr/local/share/omalauncher/mesa-zink.tar.xz"
    const val MESA_ZINK_LIB = "/usr/local/omalauncher-mesa/lib/libEGL_mesa.so"
    const val MESA_EGL_JSON = "/usr/local/share/glvnd/egl_vendor.d/50_mesa.json"
    /** Whole archive, checked before any extract. */
    const val MESA_ZINK_TAR_SHA256 = "2f9a58343997e972bd2c82bb78c6a30fbbda342ac3e0df4ad148732ced541f93"
    /** libEGL_mesa.so after extract. sha256sum follows the symlink. */
    const val MESA_ZINK_LIB_SHA256 = "bf9ea007f4babd55c86cfe87e8fd7b0cfa47e78fc34aa89b72fa60c7b84837e9"
    /**
     * The archive's sha256, written after a good extract. Zink lives in
     * libgallium, so a rebuilt bundle can keep the same libEGL; this changes anyway.
     */
    const val MESA_ZINK_STAMP = "/usr/local/omalauncher-mesa/bundle.sha256"

    private val mali = GpuDriver(
        kind = GpuKind.MALI,
        probeId = "DRIVER_ID_MESA_PANVK",
        label = "Mali",
        packages = basePackages,
        // PanVK-kbase 0.1.0 rc2, the glibc build. Tested upstream on a Pixel 9a (Mali-G715).
        url = "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v0.1.0-rc2/libvulkan_panfrost-glibc-aarch64.so",
        payload = DriverPayload.RawLibrary(
            sha256 = "0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820",
        ),
        driverPath = "/usr/local/lib64/libvulkan_panfrost.so",
        icdPath = "/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json",
        apiVersion = "1.4.363",
    )

    private val turnip = GpuDriver(
        kind = GpuKind.TURNIP,
        probeId = "DRIVER_ID_MESA_TURNIP",
        label = "Adreno",
        // The .so needs libSPIRV-Tools.so and libxcb-keysyms.so.1, which mesa does not always pull in.
        packages = basePackages + listOf("spirv-tools", "xcb-util-keysyms"),
        // lfdevs turnip-26.3.0-devel-20260824, the Arch aarch64 vulkan-freedreno package (KGSL, glibc).
        url = "https://github.com/lfdevs/mesa-for-android-container/releases/download/turnip-26.3.0-devel-20260824/vulkan-freedreno-1-26.3.0-1-aarch64.pkg.tar.xz",
        payload = DriverPayload.ArchPackage(
            packageSha256 = "9824514d7180402d936f83f4ad422f338b7df31ff2804eab8a0badb13ee44a77",
            member = "usr/lib/libvulkan_freedreno.so",
            librarySha256 = "d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f",
        ),
        driverPath = "/usr/local/lib64/libvulkan_freedreno.so",
        icdPath = "/usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        apiVersion = "1.4.359",
    )

    fun of(kind: GpuKind): GpuDriver = when (kind) {
        GpuKind.MALI -> mali
        GpuKind.TURNIP -> turnip
    }

    /** Pacman for Mesa and the loader, then the pinned blob unless that exact file is already there. */
    fun installScript(driver: GpuDriver): String {
        return pacmanInstall(driver.packages) + " && " + fetch(driver) + " && " + mesaZinkScript()
    }

    /**
     * Unpacks the patched libEGL and zink next to each other. The stamp names
     * the archive it came from, so a later APK that carries a new build replaces it.
     */
    fun mesaZinkScript(): String {
        val json = """{"file_format_version":"1.0.0","ICD":{"library_path":"$MESA_ZINK_LIB"}}"""
        val bundle = shellQuote(MESA_ZINK_BUNDLE)
        val list = shellQuote("/tmp/omalauncher-mesa.list")
        val tarOk = "echo '$MESA_ZINK_TAR_SHA256  $MESA_ZINK_BUNDLE' | sha256sum -c -"
        val libOk = "echo '$MESA_ZINK_LIB_SHA256  $MESA_ZINK_LIB' | sha256sum -c -"
        val stamp = shellQuote(MESA_ZINK_STAMP)
        // Refuse a member that leaves the prefix or walks up. Then extract.
        // Arch's tar is GNU tar. It has no --no-absolute-names (that flag is
        // bsdtar) and exits 64 before unpacking. GNU tar already strips a
        // leading slash unless --absolute-names is set, which this script
        // does not pass.
        val members =
            "tar -tJf $bundle > $list && " +
                "! grep -Ev '^usr/local/omalauncher-mesa/' $list && " +
                "! grep -E '(^/|\\.\\.)' $list"
        val unpack =
            "$tarOk && $members && tar -xJf $bundle -C / && $libOk && rm -f $list && " +
                "echo '$MESA_ZINK_TAR_SHA256' > $stamp"
        return "{ grep -qx '$MESA_ZINK_TAR_SHA256' $stamp 2>/dev/null || { $unpack; }; } && rm -f $list && " +
            "mkdir -p ${shellQuote(MESA_EGL_JSON.substringBeforeLast('/'))} && " +
            "printf '%s\\n' ${shellQuote(json)} > ${shellQuote(MESA_EGL_JSON)}"
    }

    /**
     * Vulkan finds the GPU through the driver, and OpenGL runs on it through zink.
     * Offscreen, so no window is needed. The software-rendering variable is unset
     * because checks run while apps still render on the CPU, and that setting would win.
     */
    fun probeScript(driver: GpuDriver): String {
        // The guest still has SOFTWARE_ENV here. The loader prefers VK_DRIVER_FILES
        // over VK_ICD_FILENAMES, so both have to name this ICD or the check sees no driver.
        val exports = driver.env.joinToString(" ") { entry ->
            val key = entry.substringBefore("=")
            val value = entry.substringAfter("=")
            "export $key=${shellQuote(value)};"
        }
        val failure = shellQuote("Vulkan: the ${driver.label} driver did not load")
        return "{ unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES; " + exports +
            " vk=\$(timeout 60 vulkaninfo --summary 2>/dev/null); echo \"\$vk\" | grep -E 'deviceName|driverID';" +
            " { echo \"\$vk\" | grep -q ${shellQuote(driver.probeId)} || { echo $failure; false; }; } &&" +
            " { timeout 60 eglinfo -B -p surfaceless 2>/dev/null | grep 'renderer: zink' || { echo 'OpenGL: zink did not start on the GPU'; false; }; }; }"
    }

    /** Rewrites the ICD manifest for a library that is already installed. No download, no pacman. */
    fun manifestScript(driver: GpuDriver): String =
        "{ echo '${driver.librarySha256}  ${driver.driverPath}' | sha256sum -c - && " + writeManifest(driver) + "; }"

    private fun fetch(driver: GpuDriver): String {
        val download = when (val payload = driver.payload) {
            is DriverPayload.RawLibrary -> rawDownload(driver, payload)
            is DriverPayload.ArchPackage -> packageDownload(driver, payload)
        }
        return """
            { echo '${driver.librarySha256}  ${driver.driverPath}' | sha256sum -c - 2>/dev/null || { $download; }; } &&
            ${writeManifest(driver)}
            """.trimIndent().replace("\n", " ")
    }

    private fun writeManifest(driver: GpuDriver): String {
        val icdDir = driver.icdPath.substringBeforeLast('/')
        val icd = """{"file_format_version":"1.0.1","ICD":{"library_path":"${driver.driverPath}","api_version":"${driver.apiVersion}"}}"""
        return "mkdir -p ${shellQuote(icdDir)} && printf '%s\\n' ${shellQuote(icd)} > ${shellQuote(driver.icdPath)}"
    }

    /**
     * The trap runs on any exit, including a hash or tar failure. Cleanup used
     * to sit at the end of the && chain, so a failed download left the archive
     * in the guest.
     */
    private fun rawDownload(driver: GpuDriver, payload: DriverPayload.RawLibrary): String {
        val tmp = "/tmp/omalauncher-gpu.so"
        val body = """
            curl -fL --retry 3 -o $tmp ${driver.url} &&
            echo '${payload.sha256}  $tmp' | sha256sum -c - &&
            install -Dm755 $tmp ${driver.driverPath}
            """.trimIndent().replace("\n", " ")
        return guarded(body)
    }

    private fun packageDownload(driver: GpuDriver, payload: DriverPayload.ArchPackage): String {
        requireSafeMember(payload.member)
        val pkg = "/tmp/omalauncher-gpu.pkg.tar.xz"
        val dir = "/tmp/omalauncher-gpu-extract"
        val body = """
            curl -fL --retry 3 -o $pkg ${driver.url} &&
            echo '${payload.packageSha256}  $pkg' | sha256sum -c - &&
            rm -rf $dir && mkdir -p $dir &&
            tar -xJf $pkg -C $dir ${payload.member} &&
            echo '${payload.librarySha256}  $dir/${payload.member}' | sha256sum -c - &&
            install -Dm755 $dir/${payload.member} ${driver.driverPath}
            """.trimIndent().replace("\n", " ")
        return guarded(body)
    }

    private fun guarded(body: String): String =
        "{ trap 'rm -rf /tmp/omalauncher-gpu.so /tmp/omalauncher-gpu.pkg.tar.xz /tmp/omalauncher-gpu-extract' EXIT; $body; }"

    /** A package member is a relative path. It is a constant today; this refuses a later edit that is not. */
    private fun requireSafeMember(member: String) {
        val parts = member.split('/')
        require(
            member.isNotEmpty() &&
                !member.startsWith("/") &&
                ".." !in parts &&
                parts.none { it.isEmpty() } &&
                "'" !in member &&
                " " !in member,
        ) { "unsafe archive member" }
    }
}
