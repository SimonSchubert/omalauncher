package dev.omalauncher

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException

/**
 * The app's C (src/main/cpp) built for the machine a test runs on and loaded, so a test can hold it to the Kotlin it
 * stands in for. Skips the test where there's no C compiler or JDK headers.
 */
object HostNative {
    private val loaded = HashSet<String>()

    @Synchronized
    fun load(vararg sources: String) {
        val key = sources.joinToString(",")
        if (key in loaded) return
        val files = sources.map { File("src/main/cpp/$it") }
        files.forEach { assumeTrue("run from the app module: $it", it.isFile) }
        val include = File(System.getProperty("java.home"), "include")
        val platform = include.listFiles { f -> f.isDirectory && File(f, "jni_md.h").isFile }?.firstOrNull()
        assumeTrue("no JDK headers in $include", File(include, "jni.h").isFile && platform != null)
        val suffix = if (System.getProperty("os.name").startsWith("Mac")) ".dylib" else ".so"
        val out = File.createTempFile(sources[0].substringBefore('.'), suffix).apply { deleteOnExit() }
        // As CMakeLists.txt builds them: no fused multiply-adds, so the numbers are the Kotlin's.
        val command = listOf("cc", "-O2", "-Wall", "-ffp-contract=off", "-shared", "-fPIC", "-I$include", "-I$platform", "-o", out.path) + files.map { it.path } + "-lm"
        val built =
            try {
                val p = ProcessBuilder(command).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) fail("$key doesn't build:\n$text")
                true
            } catch (e: IOException) {
                false
            }
        assumeTrue("no C compiler", built)
        System.load(out.path)
        loaded += key
    }
}
