package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class GpuTest {
    @Test fun noNodeMeansNoDriver() {
        assertEquals(null, gpuKind(hasMali = false, hasKgsl = false, kgslModel = "Adreno 740"))
    }

    @Test fun maliNodeSelectsPanvk() {
        assertEquals(GpuKind.MALI, gpuKind(hasMali = true, hasKgsl = false))
    }

    @Test fun adreno6xxSelectsTurnip() {
        assertEquals(GpuKind.TURNIP, gpuKind(hasMali = false, hasKgsl = true, kgslModel = "Adreno (TM) 642L"))
        assertEquals(GpuKind.TURNIP, gpuKind(hasMali = false, hasKgsl = true, kgslModel = "Adreno630v2"))
        assertTrue(turnipSupports("Adreno 750"))
    }

    @Test fun olderAdrenoAndUnreadableModelAreNotOffered() {
        assertEquals(null, gpuKind(hasMali = false, hasKgsl = true, kgslModel = "Adreno 540"))
        assertEquals(null, gpuKind(hasMali = false, hasKgsl = true, kgslModel = null))
        assertEquals(null, gpuKind(hasMali = false, hasKgsl = true, kgslModel = "\n"))
        assertFalse(turnipSupports("not a gpu"))
        assertEquals(540, adrenoModelNumber("Adreno 540"))
        assertEquals(642, adrenoModelNumber("Adreno (TM) 642L"))
    }

    /** A phone has one GPU. If both nodes exist, keep the Mali driver this phone already used. */
    @Test fun bothNodesStayOnMali() {
        assertEquals(GpuKind.MALI, gpuKind(hasMali = true, hasKgsl = true, kgslModel = "Adreno 740"))
    }

    @Test fun softwareEnvNamesOnlyLavapipe() {
        val icds = listOf(GpuDrivers.of(GpuKind.MALI).icdPath, GpuDrivers.of(GpuKind.TURNIP).icdPath)
        assertEquals(
            listOf(
                "LIBGL_ALWAYS_SOFTWARE=1",
                "VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json",
                "VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json",
            ),
            SOFTWARE_ENV,
        )
        assertTrue(SOFTWARE_ENV.none { line -> icds.any { line.endsWith(it) } })
    }

    @Test fun retiresOnlyOurOldMaliManifest() {
        val dir = createTempDirectory().toFile()
        val mali = GpuDrivers.of(GpuKind.MALI)
        val ours = File(dir, "ours.json").apply { writeText("""{"ICD":{"library_path":"${mali.driverPath}"}}""") }
        val mesas = File(dir, "mesa.json").apply { writeText("""{"ICD":{"library_path":"/usr/lib/libvulkan_panfrost.so"}}""") }
        GpuDrivers.retireLegacyManifest(mali, ours)
        GpuDrivers.retireLegacyManifest(mali, mesas)
        assertFalse(ours.exists())
        assertTrue(mesas.exists())
        // A link is left alone: its target isn't ours to delete.
        val link = File(dir, "link.json").toPath()
        Files.createSymbolicLink(link, File(dir, "target.json").apply { writeText(mali.driverPath) }.toPath())
        GpuDrivers.retireLegacyManifest(mali, link.toFile())
        assertTrue(Files.isSymbolicLink(link))
        // Turnip never wrote one there.
        GpuDrivers.retireLegacyManifest(GpuDrivers.of(GpuKind.TURNIP), mesas.apply { writeText(mali.driverPath) })
        assertTrue(mesas.exists())
        dir.deleteRecursively()
    }

    @Test fun maliScriptStaysThePinnedPanvkBlob() {
        val driver = GpuDrivers.of(GpuKind.MALI)
        val script = GpuDrivers.installScript(driver)
        assertTrue(script.contains("libvulkan_panfrost-glibc-aarch64.so"))
        assertTrue(script.contains("0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820  /usr/local/lib64/libvulkan_panfrost.so"))
        assertTrue(script.contains("0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820  /tmp/omalauncher-gpu.so"))
        assertTrue(script.contains("\"api_version\":\"1.4.363\""))
        assertTrue(script.contains("> '/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json'"))
        assertTrue(trapCoversCurl(script))
        assertFalse(script.contains("/tmp/panvk.so"))
        assertFalse(script.contains("turnip"))
        assertFalse(script.contains("spirv-tools"))
        val probe = GpuDrivers.probeScript(driver)
        assertTrue(probe.contains("echo \"\$vk\""))
        assertTrue(probe.contains("grep -q 'DRIVER_ID_MESA_PANVK'"))
        assertTrue(probe.contains("Vulkan: the Mali driver did not load"))
        assertTrue(probe.contains("export VK_ICD_FILENAMES='/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json';"))
        assertTrue(probe.contains("export VK_DRIVER_FILES='/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json';"))
        assertTrue(probe.contains("unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES"))
        assertTrue(probe.contains("export MESA_VK_WSI_DEBUG='sw';"))
        assertTrue(probe.contains("export GALLIUM_DRIVER='zink';"))
        assertTrue(driver.env.any { it == "GALLIUM_DRIVER=zink" })
        // Stock Mesa drops zink on the shm path and shows black windows; Mali uses the patched bundle too.
        assertTrue(probe.contains("export MESA_LOADER_DRIVER_OVERRIDE='zink';"))
        assertTrue(probe.contains("export __EGL_VENDOR_LIBRARY_FILENAMES='/usr/local/share/glvnd/egl_vendor.d/50_mesa.json';"))
        assertTrue(script.contains("tar -xJf '/usr/local/share/omalauncher/mesa-zink.tar.xz' -C '/usr/local/.omalauncher-mesa-staging' &&"))
    }

    @Test fun turnipScriptPinsTheKgslPackageAndTheExtractedLibrary() {
        val driver = GpuDrivers.of(GpuKind.TURNIP)
        val script = GpuDrivers.installScript(driver)
        assertTrue(script.contains("turnip-26.3.0-devel-20260824/vulkan-freedreno-1-26.3.0-1-aarch64.pkg.tar.xz"))
        assertTrue(script.contains("9824514d7180402d936f83f4ad422f338b7df31ff2804eab8a0badb13ee44a77  /tmp/omalauncher-gpu.pkg.tar.xz"))
        assertTrue(script.contains("d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f  /usr/local/lib64/libvulkan_freedreno.so"))
        assertTrue(script.contains("d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f  /tmp/omalauncher-gpu-extract/usr/lib/libvulkan_freedreno.so"))
        assertTrue(script.contains("tar -xJf /tmp/omalauncher-gpu.pkg.tar.xz -C /tmp/omalauncher-gpu-extract usr/lib/libvulkan_freedreno.so"))
        assertTrue(script.contains("'spirv-tools'"))
        assertTrue(script.contains("'xcb-util-keysyms'"))
        assertTrue(script.contains("\"api_version\":\"1.4.359\""))
        assertTrue(script.contains("> '/usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json'"))
        assertTrue(trapCoversCurl(script))
        assertFalse(script.contains("panvk"))
        assertFalse(script.contains("/tmp/panvk.so"))
        val probe = GpuDrivers.probeScript(driver)
        assertTrue(probe.contains("grep -q 'DRIVER_ID_MESA_TURNIP'"))
        assertTrue(probe.contains("Vulkan: the Adreno driver did not load"))
        assertTrue(probe.contains("export VK_ICD_FILENAMES='/usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json';"))
        assertTrue(probe.contains("export VK_DRIVER_FILES='/usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json';"))
        assertTrue(probe.contains("unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES"))
        assertTrue(probe.contains("export MESA_LOADER_DRIVER_OVERRIDE='zink';"))
        assertTrue(probe.contains("export GALLIUM_DRIVER='zink';"))
        assertTrue(probe.contains("export LIBGL_DRIVERS_PATH='/usr/local/omalauncher-mesa/lib/dri';"))
        assertTrue(probe.contains("export __EGL_VENDOR_LIBRARY_FILENAMES='/usr/local/share/glvnd/egl_vendor.d/50_mesa.json';"))
        val extractAt = script.indexOf("tar -xJf '/usr/local/share/omalauncher/mesa-zink.tar.xz' -C '/usr/local/.omalauncher-mesa-staging' &&")
        val tarHashAt = script.indexOf("2f9a58343997e972bd2c82bb78c6a30fbbda342ac3e0df4ad148732ced541f93")
        assertTrue(tarHashAt >= 0 && extractAt > tarHashAt)
        assertFalse(script.contains("--absolute-names"))
        assertTrue(script.contains("! grep -Ev '^usr/local/omalauncher-mesa/'"))
        assertTrue(script.contains("/usr/local/omalauncher-mesa/lib/libEGL_mesa.so"))
        assertTrue(script.contains("bf9ea007f4babd55c86cfe87e8fd7b0cfa47e78fc34aa89b72fa60c7b84837e9"))
        // A rebuilt bundle can keep libEGL, so the skip is on the archive's stamp, written after the extract.
        assertTrue(script.contains("{ grep -qx '2f9a58343997e972bd2c82bb78c6a30fbbda342ac3e0df4ad148732ced541f93' '/usr/local/omalauncher-mesa/bundle.sha256'"))
        assertTrue(script.indexOf("> '/usr/local/omalauncher-mesa/bundle.sha256'") > extractAt)
        assertEquals("Adreno", driver.label)
    }

    /**
     * A crash mid-install loses what was still in the page cache. The bundle is checked
     * and flushed in staging, swapped in, and only then stamped; a stamped install still
     * has to match the sums written at unpack.
     */
    @Test
    fun mesaBundleIsStampedOnlyOnceWholeAndOnDisk() {
        val script = GpuDrivers.mesaZinkScript()
        val extractAt = script.indexOf("tar -xJf '/usr/local/share/omalauncher/mesa-zink.tar.xz' -C '/usr/local/.omalauncher-mesa-staging'")
        val libCheckAt = script.indexOf("bf9ea007f4babd55c86cfe87e8fd7b0cfa47e78fc34aa89b72fa60c7b84837e9  /usr/local/.omalauncher-mesa-staging/usr/local/omalauncher-mesa/lib/libEGL_mesa.so")
        val sumsAt = script.indexOf("find . -type f ! -name files.sha256 -exec sha256sum {} + > files.sha256")
        val firstSyncAt = script.indexOf("&& sync &&")
        val swapAt = script.indexOf("mv '/usr/local/.omalauncher-mesa-staging/usr/local/omalauncher-mesa' '/usr/local/omalauncher-mesa'")
        val stampAt = script.indexOf("> '/usr/local/omalauncher-mesa/bundle.sha256'")
        assertTrue(extractAt in 0 until libCheckAt)
        assertTrue(libCheckAt < sumsAt && sumsAt < firstSyncAt && firstSyncAt < swapAt && swapAt < stampAt)
        assertTrue(script.contains("> '/usr/local/omalauncher-mesa/bundle.sha256' && sync"))
        // The stamp alone isn't enough to skip the unpack: the files have to match their sums too.
        assertTrue(script.contains("2>/dev/null && ( cd '/usr/local/omalauncher-mesa' && sha256sum -c --quiet files.sha256"))
    }

    /** The trap is registered before curl, so a failed hash still deletes the temp files. */
    private fun trapCoversCurl(script: String): Boolean {
        val trap = script.indexOf("trap 'rm -rf /tmp/omalauncher-gpu.so /tmp/omalauncher-gpu.pkg.tar.xz /tmp/omalauncher-gpu-extract' EXIT")
        val curl = script.indexOf("curl -fL")
        return trap >= 0 && curl > trap
    }
}
