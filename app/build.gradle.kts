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
// ARM64 phone builds package the verified JNI libraries prepared by the guest
// helper. Normal host builds keep the Android Gradle Plugin's CMake integration
// unchanged.
val usePrebuiltNative = providers.gradleProperty("omalauncher.prebuiltNative")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    if (!usePrebuiltNative) {
        ndkVersion = prootNdk
    }

    defaultConfig {
        applicationId = "dev.omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 20
        versionName = "0.11.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    if (!usePrebuiltNative) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }

    packaging {
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
        }
    }

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
        // A side-by-side phone-local build. It has a separate package name and
        // can therefore be installed without the production/debug launcher's
        // signing key. Keep the normal debug build unchanged for development
        // installs from the host.
        create("custom") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".custom"
            versionNameSuffix = "-custom"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

// ExamplesTest reads the widget examples and the README from here, so editing them reruns it.
tasks.withType<Test>().configureEach { inputs.dir("src/main/assets/widgets") }

// Gitignored, built by scripts/fetch-proot.sh. Without it a build still succeeds, but every Linux command fails.
tasks.matching { it.name == "preDebugBuild" || it.name == "preCustomBuild" || it.name == "preReleaseBuild" }.configureEach {
    val proot = file("src/main/jniLibs/arm64-v8a/libproot.so")
    doFirst { check(proot.exists()) { "$proot is missing; run scripts/fetch-proot.sh" } }
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
    implementation(libs.bcpg)
    implementation(libs.androidsvg)
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(project(":owl"))
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
