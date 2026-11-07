// Trackhop's screens and connectors as a library, opened from Vivi's settings ("Playlist sync").
plugins {
    id("com.android.library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Optional official-API keys: -PNAME=value or an environment variable, as upstream's build reads them.
fun cfg(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.takeIf { it.isNotEmpty() }
        ?: default

val googleClientId = cfg("GOOGLE_CLIENT_ID")
val googleScheme = if (googleClientId.isNotEmpty()) {
    "com.googleusercontent.apps." + googleClientId.removeSuffix(".apps.googleusercontent.com")
} else {
    "com.googleusercontent.apps.unconfigured"
}

android {
    namespace = "com.xlollx.songport"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        fun stringField(name: String, value: String) = buildConfigField("String", name, "\"$value\"")
        stringField("VERSION_NAME", "1.15.1-vivi")
        buildConfigField("int", "VERSION_CODE", "166")
        stringField("SPOTIFY_CLIENT_ID", cfg("SPOTIFY_CLIENT_ID"))
        stringField("TIDAL_CLIENT_ID", cfg("TIDAL_CLIENT_ID"))
        stringField("DEEZER_APP_ID", cfg("DEEZER_APP_ID"))
        stringField("DEEZER_REDIRECT_URL", cfg("DEEZER_REDIRECT_URL", "https://xlollx.github.io/Songport/deezer-redirect.html"))
        stringField("GOOGLE_CLIENT_ID", googleClientId)
        stringField("APPLE_DEVELOPER_TOKEN", cfg("APPLE_DEVELOPER_TOKEN"))
        stringField("LASTFM_API_KEY", cfg("LASTFM_API_KEY"))
        stringField("GOOGLE_REDIRECT_SCHEME", googleScheme)
        stringField("PRIVACY_POLICY_URL", "")
        stringField("KOFI_URL", "")
        stringField("SPONSORS_URL", "")
        buildConfigField("boolean", "PLUGIN_LINKS", "false")
        buildConfigField("boolean", "STORE_BUILD", "false")

        manifestPlaceholders["googleRedirectScheme"] = googleScheme
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycle.get()}")

    implementation(libs.compose.ui)
    implementation(libs.material3)
    implementation(libs.compose.reorderable)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.haze)
    implementation("dev.chrisbanes.haze:haze-materials:${libs.versions.haze.get()}")

    implementation(libs.work.runtime.ktx)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    api(project(":trackhop-core"))
    implementation(project(":trackhop-bridge"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
