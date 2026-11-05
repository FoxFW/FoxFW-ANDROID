import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Optional local release-signing config — NEVER committed (git-ignored; see keystore.properties.sample).
// Contributors need none of this: with no keystore.properties present, release builds fall back to the
// debug key so the app still builds and installs. Only the official signed release uses a real keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseKeystore?.exists() == true

android {
    namespace = "com.foxfw.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foxfw.companion"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "3.0"
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
        // Only created when a local keystore.properties points at a real keystore. No secrets here.
        if (hasReleaseKeystore) {
            create("foxfw") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Personal build: Hue (and any other private-only tiles) are compiled in.
            buildConfigField("boolean", "PRIVATE_BUILD", "true")
        }
        release {
            isMinifyEnabled = false
            // Official release uses the foxfw key when keystore.properties is present; otherwise fall
            // back to the debug key so anyone can build/install a release without any keystore.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("foxfw")
                            else signingConfigs.getByName("debug")
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
        // Optional: set foxfw.buildPython in a local (git-ignored) gradle.properties, or pass
        // -Pfoxfw.buildPython=<path>. If unset, Chaquopy auto-detects python on PATH; if none is
        // found the build still succeeds (raw .py is bundled, only the .pyc precompile is skipped).
        (project.findProperty("foxfw.buildPython") as String?)?.let { buildPython(it) }
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
