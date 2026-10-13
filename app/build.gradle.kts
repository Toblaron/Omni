plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.omni.inbox"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.omni.inbox"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material3:material3")
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation("androidx.sqlite:sqlite:2.7.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    // Gemini Nano through Android AICore: summaries and reply suggestions computed on the phone
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    testImplementation("junit:junit:4.13.2")
}
