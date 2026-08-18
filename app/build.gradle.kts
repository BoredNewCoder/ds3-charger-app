import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing creds live in keystore.properties (gitignored, not
// committed) - keeps the keystore password out of source control while
// still letting assembleRelease produce an installable, signed APK.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.ds3charger.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ds3charger.app"
        // Bumped from 21 to 24 - dev.rikka.shizuku:api/provider (needed for the optional
        // analog trigger feature) declare minSdk 23/24 in their own manifests, and the
        // manifest merger fails otherwise. Safe in practice: every real device this app
        // targets (Shield TV Pro runs Android 11) is well above this floor.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Shield TV Pro is arm64 - no need to ship other ABIs (matches the
        // sibling 8bitdo-xbox-bridge project's real uinput setup, same reasoning).
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // Analog L2/R2 trigger injection needs /dev/uinput write access, which only the ADB
    // shell UID has on this device (confirmed live in the sibling 8bitdo-xbox-bridge
    // project) - Shizuku is how a normal app process gets a shell-UID service without
    // requiring root. Optional at runtime: the app works fully without Shizuku installed,
    // just without this one feature (see Prefs.isAnalogTriggersEnabled + the graceful
    // fallback in Ds3ChargerService's setupShizuku()).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
