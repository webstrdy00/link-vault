import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun buildConfigString(name: String): String {
    val value = providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name)).getOrElse("")
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
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
