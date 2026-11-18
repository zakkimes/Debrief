import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kimes.daq"
    compileSdk = 35

    // The release key lives outside the repo, in ~/.debrief (keystore.properties + release.jks). Every
    // build is signed with it, so each version installs over the last and keeps the tablet's data.
    // Without it (someone else's computer) builds fall back to that machine's debug key.
    val keyProps = File(System.getProperty("user.home"), ".debrief/keystore.properties").takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } to f.parentFile }
    // -Plegacy: the old package (com.kimes.daq) on the old debug key, only to install over the app
    // from before 0.23 so it can make a backup to move into the new one
    val legacy = project.hasProperty("legacy")
    signingConfigs {
        if (keyProps != null) create("release") {
            val (p, dir) = keyProps
            storeFile = File(dir, p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    val signing = if (keyProps != null && !legacy) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    defaultConfig {
        applicationId = if (legacy) "com.kimes.daq" else "com.kimes.debrief"
        minSdk = 29
        targetSdk = 35
        versionCode = 25
        versionName = "0.25"
    }

    buildTypes {
        debug { signingConfig = signing }
        release {
            // Shrunk and optimized (about a quarter of the debug size). Signed with the same key
            // as the debug builds so it installs over them and keeps every session.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
