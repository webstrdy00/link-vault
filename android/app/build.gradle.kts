import java.net.URI
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

fun buildConfigValue(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name)).getOrElse("")

fun buildConfigString(name: String): String {
    val value = buildConfigValue(name)
    if (name == "SUPABASE_PUBLISHABLE_KEY" && value.isNotBlank()) {
        val jwtPayload = runCatching {
            String(Base64.getUrlDecoder().decode(value.split('.').getOrNull(1).orEmpty()))
        }.getOrDefault("")
        require(
            !value.trim().startsWith("sb_secret_") &&
                !Regex("\"role\"\\s*:\\s*\"service_role\"").containsMatchIn(jwtPayload),
        ) { "Server credentials must never be embedded in the Android app." }
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n") + "\""
}

val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
val releaseSigningValues = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningValues.values.any { it != null }
val releaseStoreFile = if (hasReleaseSigning) {
    val missingNames = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
    require(missingNames.isEmpty()) {
        "Release signing requires all four environment variables; missing or blank: ${missingNames.joinToString()}"
    }
    val storeFile = runCatching {
        file(checkNotNull(releaseSigningValues["ANDROID_KEYSTORE_PATH"]))
            .takeIf { it.isFile }
    }.getOrNull()
    requireNotNull(storeFile) { "ANDROID_KEYSTORE_PATH must identify an existing file." }
} else {
    null
}

if (requireReleaseSigning) {
    require(releaseStoreFile != null) {
        "requireReleaseSigning=true requires all four Android release signing environment variables."
    }
    val supabaseUri = runCatching {
        URI(buildConfigValue("SUPABASE_URL").trim().trimEnd('/'))
    }.getOrNull()
    require(
        supabaseUri != null &&
            supabaseUri.scheme.equals("https", ignoreCase = true) &&
            supabaseUri.host?.isNotBlank() == true &&
            supabaseUri.userInfo == null &&
            supabaseUri.rawQuery == null &&
            supabaseUri.rawFragment == null &&
            (supabaseUri.rawPath.isNullOrEmpty() || supabaseUri.rawPath == "/") &&
            (supabaseUri.port == -1 || supabaseUri.port in 1..65535),
    ) { "SUPABASE_URL must be a valid HTTPS origin when requireReleaseSigning=true." }
    listOf("SUPABASE_PUBLISHABLE_KEY", "GOOGLE_WEB_CLIENT_ID").forEach { name ->
        require(buildConfigValue(name).isNotBlank()) {
            "$name is required when requireReleaseSigning=true."
        }
    }
}

android {
    namespace = "com.linkvault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linkvault.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildConfigString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", buildConfigString("GOOGLE_WEB_CLIENT_ID"))
    }

    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    if (providers.gradleProperty("localBackendTests").orNull == "true") {
        sourceSets.getByName("androidTest").java.srcDir("src/localBackendTest/java")
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.korean)
    implementation(libs.androidx.exifinterface)
    kapt(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
