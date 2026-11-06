plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.music.vivi.projector"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vivi.vivimusic.projector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

    }

    // One small APK per architecture: armeabi-v7a (32-bit, e.g. Wanbo X2 Pro) and arm64-v8a (64-bit)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":innertube"))
    implementation(libs.media3)
    implementation(libs.zxing.core)
    implementation(libs.coroutines.guava)
    implementation(libs.activity)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.viewmodel)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    coreLibraryDesugaring(libs.desugaring)
}
