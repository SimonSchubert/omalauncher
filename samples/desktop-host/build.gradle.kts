plugins { alias(libs.plugins.android.application) }
val bridgePreview = providers.gradleProperty("omalauncher.bridgePreview").orNull == "true"
val providerPackage = if (bridgePreview) "dev.omalauncher.bridgepreview" else "dev.omalauncher"

android {
    namespace = "dev.omalauncher.sample.desktop"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.omalauncher.sample.desktop"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PROVIDER_PACKAGE", "\"$providerPackage\"")
        manifestPlaceholders["providerPackage"] = providerPackage
    }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("main").aidl.srcDir("../../app/src/main/aidl")
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
