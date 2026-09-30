plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.slate.tablet"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.slate.tablet"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 cuts the Anthropic SDK's dependencies (Jackson, OkHttp, Kotlin reflection) down to
            // what's used; proguard-rules.pro keeps the classes they reach by reflection.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so a fresh clone can build an installable release APK; use your own key to publish.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.66.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
    // Real org.json for unit tests (Android's is a stub off-device).
    testImplementation("org.json:json:20240303")
}
