import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Default allowlist for all tablets, baked into the APK at build time.
// Edit watchdog.properties in the project root.
val watchdog = Properties().apply {
    val f = rootProject.file("watchdog.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun wd(key: String, default: String = ""): String =
    "\"" + watchdog.getProperty(key, default).trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Your own release signing key. keystore.properties lives in the project root,
// is listed in .gitignore and must NEVER be committed. See README > "Signing key".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

val appVersionName = "2.1.0"

android {
    namespace = "com.rutg3r.installwatchdog"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.rutg3r.installwatchdog"
        minSdk = 24          // Android 7.0 (Samsung Tab S2 stock) and newer
        targetSdk = 34
        versionCode = 6
        versionName = appVersionName

        buildConfigField("String", "DEFAULT_ALLOWLIST", wd("allowlist", "Kiosk Satellite"))
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Your own key when keystore.properties is present, otherwise the
            // debug key (fine for your own tablets, not for public downloads).
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// APK file name: InstallWatchdog-2.1.0-release.apk / -debug.apk
base {
    archivesName.set("InstallWatchdog-$appVersionName")
}

// No third-party dependencies: plain Android framework + Kotlin stdlib only.
dependencies {}
