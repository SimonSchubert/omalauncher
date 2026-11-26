import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from the gitignored keystore.properties at the repo root.
val uploadKey = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// The NDK proot is built with (scripts/fetch-proot.sh); wlbridge uses the same one.
val prootNdk = rootProject.file("scripts/fetch-proot.sh").readLines().first { it.startsWith("NDK_VERSION=") }.substringAfter('=')

// sherpa-onnx, for on-device speech (Parakeet), isn't on Maven: its release AAR, fetched once into the gitignored
// app/libs and checked against its SHA-256. Its other ABIs drop out with abiFilters.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar").also { aar ->
    if (aar.isFile) return@also
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Fetching $url")
    val part = File(aar.path + ".part")
    aar.parentFile.mkdirs()
    uri(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
    check(sha == sherpaSha256) { "$url has SHA-256 $sha, not $sherpaSha256" }
    part.renameTo(aar)
}

android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    ndkVersion = prootNdk

    defaultConfig {
        applicationId = "dev.omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 48
        versionName = "1.12.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    // play: the AAB for Google Play, with Play's in-app updates, whose library is closed source. foss: the APK on
    // GitHub releases, which F-Droid builds too, without it; its LauncherUpdates asks GitHub releases instead.
    flavorDimensions += "store"
    productFlavors {
        create("foss") {
            dimension = "store"
            isDefault = true
        }
        create("play") { dimension = "store" }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
            // sherpa-onnx's C and C++ APIs: the app only uses its JNI library.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    // Every language's text in every install: settings can pick a language the phone isn't set to (AppLanguage), and
    // Play's language splits would only carry the phone's.
    bundle { language { enableSplit = false } }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        create("release") {
            uploadKey?.let {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // en-XA and ar-XB: text that stays plain English or left-to-right there isn't in res/ yet.
        debug { isPseudoLocalesEnabled = true }
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-sherpa.pro")
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

// ExamplesTest reads the widget examples and the README from here, so editing them reruns it.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/widgets")
    // The native tests build these for the machine they run on (HostNative).
    inputs.dir("src/main/cpp")
}

// Gitignored, built by scripts/fetch-proot.sh. Without it a release still builds, but every Linux command fails.
tasks.matching { it.name.matches(Regex("pre\\w*ReleaseBuild")) }.configureEach {
    val proot = file("src/main/jniLibs/arm64-v8a/libproot.so")
    doFirst { check(proot.exists()) { "$proot is missing; run scripts/fetch-proot.sh" } }
}

// The libraries don't follow a pull: built before patches/proot or the script changed, they make an app with
// an old proot (and no fast lane library) that builds and installs like any other. The script leaves a hash
// of what it built from in .source; a debug build without any libraries stays possible (CI lints that way).
tasks.matching { it.name.matches(Regex("pre\\w*(Debug|Release)Build")) }.configureEach {
    val libs = file("src/main/jniLibs/arm64-v8a")
    val root = rootProject.projectDir
    val sources = listOf(rootProject.file("scripts/fetch-proot.sh")) +
        rootProject.fileTree("patches/proot").files.filter { !it.name.startsWith(".") }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    inputs.files(sources)
    doFirst {
        if (!File(libs, "libproot.so").exists()) return@doFirst
        val digest = MessageDigest.getInstance("SHA-256")
        sources.forEach { digest.update(it.readBytes()) }
        val now = digest.digest().joinToString("") { "%02x".format(it) }
        val built = File(libs, ".source").takeIf { it.isFile }?.readText()?.trim()
        check(built == now) { "$libs was built from an older patches/proot or scripts/fetch-proot.sh; run scripts/fetch-proot.sh" }
    }
}

// libghostty-vt, the built-in terminal's engine, built by scripts/fetch-ghostty-vt.sh into the gitignored
// src/main/cpp/ghostty-vt. Without it the app builds without the built-in terminal; built by an older script (another
// ghostty or Zig), it would build an app with an engine the code may not fit.
tasks.matching { it.name.matches(Regex("pre\\w*(Debug|Release)Build")) }.configureEach {
    val vt = file("src/main/cpp/ghostty-vt")
    val script = rootProject.file("scripts/fetch-ghostty-vt.sh")
    inputs.file(script)
    doFirst {
        val built = File(vt, ".source").takeIf { it.isFile }?.readText()?.trim() ?: return@doFirst
        val now = MessageDigest.getInstance("SHA-256").digest(script.readBytes()).joinToString("") { "%02x".format(it) }
        check(built == now) { "$vt was built by an older scripts/fetch-ghostty-vt.sh; run it again" }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.commons.compress)
    implementation(libs.androidsvg)
    implementation(libs.zxing.core)
    implementation(project(":owl"))
    implementation(files(sherpaAar))
    "playImplementation"(libs.play.app.update)
    // Play's library brings fragment 1.0.0, too old for registerForActivityResult.
    "playImplementation"(libs.androidx.fragment)
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
