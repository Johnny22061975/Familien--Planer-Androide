plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "de.familienplaner.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "de.familienplaner.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
        buildConfigField("String", "AC_DEBUG", "\"" + (System.getenv("FP_AC_DEBUG") ?: "").replace("\"", "") + "\"")
    }
    buildFeatures { buildConfig = true }
    val ksPath = System.getenv("FP_KS_PATH")
    if (ksPath != null && File(ksPath).exists()) {
        signingConfigs {
            create("fp") {
                storeFile = File(ksPath)
                storePassword = System.getenv("FP_KS_PASS")
                keyAlias = System.getenv("FP_KS_ALIAS")
                keyPassword = System.getenv("FP_KEY_PASS")
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("fp") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(platform("com.google.firebase:firebase-bom:32.8.1"))
    implementation("com.google.firebase:firebase-messaging")
}
