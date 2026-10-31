plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.foxfw.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foxfw.companion"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.1"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    ndkVersion = "26.1.10909125"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("foxfw") {
            storeFile = file("C:/Users/stone/android-build-tools/foxfw-release.keystore")
            storePassword = "foxfw2025"
            keyAlias = "foxfw"
            keyPassword = "foxfw2025"
        }
    }
    buildTypes {
        getByName("debug") {
            // Personal build: Hue (and any other private-only tiles) are compiled in.
            buildConfigField("boolean", "PRIVATE_BUILD", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("foxfw")
            // Distributed build: private-only tiles are hidden.
            buildConfigField("boolean", "PRIVATE_BUILD", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

chaquopy {
    defaultConfig {
        buildPython("C:/Users/stone/radioconda/python.exe")
        pip { }  // arke engine is pure-Python, no deps
    }
    sourceSets {
        getByName("main") { srcDir("src/main/python") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Vendored test jars (this machine's JDK cacerts can't validate the Google/Maven TLS
    // chain for gradle, though curl can — so unit-test deps are local files + --offline).
    testImplementation(files("libs/junit-4.13.2.jar", "libs/hamcrest-core-1.3.jar", "libs/json-20231013.jar"))
}
