import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.smartphonex.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.smartphonex.app"
        minSdk = 26
        targetSdk = 34
        // One place for the version: gradle.properties.
        versionCode = providers.gradleProperty("spx.versionCode").get().toInt()
        versionName = providers.gradleProperty("spx.versionName").get()
        // Where the app looks for newer releases. Empty turns the check off.
        buildConfigField("String", "UPDATE_URL", "\"${providers.gradleProperty("spx.updateUrl").getOrElse("")}\"")
    }
    // Release signing comes from keystore.properties (never committed) or, in CI, environment variables.
    // Without either, release builds are left unsigned. Losing the key means users can't update, so back it up.
    val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
    val storePath = secret("storeFile", "SPX_KEYSTORE_FILE")
    signingConfigs {
        if (storePath != null) create("release") {
            storeFile = rootProject.file(storePath)
            storePassword = secret("storePassword", "SPX_KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "SPX_KEY_ALIAS")
            keyPassword = secret("keyPassword", "SPX_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Shrinking stays off until Ktor, serialization and USB serial have tested keep rules.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        // The report is kept and reviewed; CI fails only on new errors once a baseline exists.
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
    }
}
dependencies {
    val ktor = "2.3.13"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
