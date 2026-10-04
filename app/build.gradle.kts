import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------------
// Signing
//
// A release key is read from (in this order):
//   1. keystore.properties in the repository root - gitignored, never commit it
//   2. environment variables, which is what CI uses:
//        LIVEREPLY_KEYSTORE_FILE      path to the .jks / .keystore / .p12 file
//        LIVEREPLY_KEYSTORE_PASSWORD  keystore password
//        LIVEREPLY_KEY_ALIAS          key alias inside the keystore
//        LIVEREPLY_KEY_PASSWORD       key password (falls back to the keystore password)
//
// With no key configured the build still works: `assembleRelease` produces an *unsigned*
// app-release-unsigned.apk, and the configuration message below says so. Nothing is
// faked and no key is ever committed or generated into the source tree.
// ---------------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFilePath = signingValue("storeFile", "LIVEREPLY_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "LIVEREPLY_KEYSTORE_PASSWORD")
val releaseKeyAliasValue = signingValue("keyAlias", "LIVEREPLY_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "LIVEREPLY_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning =
    releaseStoreFilePath != null && releaseStorePassword != null && releaseKeyAliasValue != null

if (hasReleaseSigning) {
    logger.lifecycle(
        "Live AI Reply: signing with alias '$releaseKeyAliasValue' " +
            "(${if (keystorePropertiesFile.exists()) "keystore.properties" else "environment variables"})."
    )
} else {
    logger.lifecycle(
        "Live AI Reply: no release signing key configured - `assembleRelease` will produce an " +
            "UNSIGNED apk. See README -> \"Signing a release build\" (keystore.properties or " +
            "LIVEREPLY_KEYSTORE_* environment variables)."
    )
}

android {
    namespace = "com.liveaireply.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.liveaireply.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        // No API keys, endpoints or secrets are baked into the build. Everything the
        // assistant needs at runtime is entered by the user and stored encrypted.
        buildConfigField("boolean", "SHIPS_WITH_DEFAULT_API_KEY", "false")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAliasValue
                keyPassword = releaseKeyPassword
                // v1 (JAR) keeps Android 6 and older installable; v2/v3 are what modern
                // devices verify first. All three are signed.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        getByName("debug") {
            // Uses ~/.android/debug.keystore, created automatically by AGP.
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ""
            if (hasReleaseSigning) {
                // Sign debug builds with the release key too, so debug and release builds
                // share one identity and can update each other in place instead of failing
                // with "App not installed" (signature mismatch). It is still debuggable.
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without a key: no signingConfig -> app-release-unsigned.apk, which is
            // reported by the build and by CI rather than silently shipped.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Network transport for OpenAI-compatible providers.
    implementation(libs.okhttp)

    // On-device OCR fallback (never uploads pixels off the device).
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
