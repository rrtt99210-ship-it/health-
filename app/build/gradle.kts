plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.svc.health"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.svc.health"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TOKEN", "\"${project.findProperty("dToken") ?: ""}\"")
        buildConfigField("String", "CANAL", "\"${project.findProperty("dCanal") ?: ""}\"")
        buildConfigField("String", "SECRET", "\"${project.findProperty("dSecret") ?: "change_me"}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}