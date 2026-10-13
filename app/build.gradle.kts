import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from the gitignored keystore.properties at the repo root.
val uploadKey = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
val integrationTestAbi = providers.gradleProperty("omalauncher.testAbi").orNull

android {
    namespace = "dev.omalauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.omalauncher"
        manifestPlaceholders["appLabel"] = "Omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 12
        versionName = "0.7.0"
        // x86_64 supports isolated broker instrumentation on an emulator; releases stay arm64.
        ndk { abiFilters += integrationTestAbi ?: "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { buildConfig = true }
    if (integrationTestAbi != null) sourceSets.getByName("debug").jniLibs.srcDir("src/testRuntime/jniLibs")

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
        debug {
            applicationIdSuffix = if (integrationTestAbi != null) ".integrationtest" else ".debug"
            manifestPlaceholders["appLabel"] = "omalauncher_debug"
        }
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
