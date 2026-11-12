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

android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    ndkVersion = prootNdk

    defaultConfig {
        applicationId = "dev.omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 36
        versionName = "1.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

// ExamplesTest reads the widget examples and the README from here, so editing them reruns it.
tasks.withType<Test>().configureEach { inputs.dir("src/main/assets/widgets") }

// Gitignored, built by scripts/fetch-proot.sh. Without it a release still builds, but every Linux command fails.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val proot = file("src/main/jniLibs/arm64-v8a/libproot.so")
    doFirst { check(proot.exists()) { "$proot is missing; run scripts/fetch-proot.sh" } }
}

// The libraries don't follow a pull: built before patches/proot or the script changed, they make an app with
// an old proot (and no fast lane library) that builds and installs like any other. The script leaves a hash
// of what it built from in .source; a debug build without any libraries stays possible (CI lints that way).
tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
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
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
