import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing (section 37, v0.2.0): never a keystore
// committed to this repository. Credentials come from a gitignored
// android/relite-home/keystore.properties (local dev) or environment
// variables (CI secrets) — whichever is present. If neither is set, no
// "release" signingConfig is registered at all and `assembleRelease`
// still produces a normal, working, R8-minified but *unsigned* APK; it's
// scripts/package-release.sh's job to never present that as a signed
// release artifact (see docs/releasing.md).
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(propertyKey: String, envVar: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(envVar))?.ifBlank { null }

val releaseStoreFile = signingProperty("storeFile", "RELITE_RELEASE_STORE_FILE")
val releaseStorePassword = signingProperty("storePassword", "RELITE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingProperty("keyAlias", "RELITE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("keyPassword", "RELITE_RELEASE_KEY_PASSWORD")
val presentCredentialCount = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .count { it != null }
val hasReleaseSigningCredentials = presentCredentialCount == 4

// Section 32 (v0.3.0): a partially-configured signing setup must fail
// loudly, not silently fall back to an unsigned release build that
// scripts/package-release.sh could then mislabel — configuration
// guessing is exactly what let that happen before.
if (presentCredentialCount in 1..3) {
    throw GradleException(
        "Incomplete release-signing configuration: $presentCredentialCount of 4 credentials " +
            "present (storeFile/storePassword/keyAlias/keyPassword). Set all four or none.",
    )
}

android {
    namespace = "io.relite.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.relite.home"
        // minSdk 26 covers realme C71 / UMS9230-family firmware generations without
        // assuming a specific one — relite/device.py reads the real ro.build.version.sdk.
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigningCredentials) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Deliberately minimal: no networking, no analytics, no Compose runtime
    // overhead on a low-RAM target device.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws at runtime; the real
    // implementation is needed for WorkspaceRepository's JVM unit tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
