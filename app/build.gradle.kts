plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.beardedskunk.homeshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.beardedskunk.homeshare"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // R8/Minify auch im Debug-Build, weil wir Debug-APKs ausrollen UND damit
        // material-icons-extended (riesig) auf die paar genutzten Icons getreeshaked wird.
        // isDebuggable bleibt true -> adb run-as etc. funktioniert weiter.
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.nanohttpd)
    implementation(libs.commons.net)
    // #10: QR erzeugen (ZXing core) + scannen (fertige Scanner-UI, keine Play-Services).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
