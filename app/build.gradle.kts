import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets live in local.properties (never committed):
//   WHOOP_CLIENT_ID=...
//   WHOOP_CLIENT_SECRET=...
//   GEMINI_API_KEY=...            (Google AI Studio key; enables Gemini photo analysis)
//   GEMINI_MODEL=gemini-3.8-flash  (optional override)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(name: String, default: String = "") = "\"" + (localProps.getProperty(name)?.trim()?.ifEmpty { null } ?: default) + "\""

android {
    namespace = "com.example.aarogya"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.aarogya"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "WHOOP_CLIENT_ID", localProp("WHOOP_CLIENT_ID"))
        buildConfigField("String", "WHOOP_CLIENT_SECRET", localProp("WHOOP_CLIENT_SECRET"))
        buildConfigField("String", "GEMINI_API_KEY", localProp("GEMINI_API_KEY"))
        buildConfigField("String", "GEMINI_MODEL", localProp("GEMINI_MODEL", "gemini-3.8-flash"))
    }

    // ONNX Runtime's native lib is ~34 MB per ABI: phones only need arm64,
    // debug builds also keep x86_64 so the app runs on the emulator.
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.image.labeling.custom)
    implementation(libs.play.services.auth)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json; android.jar only has stubs
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
