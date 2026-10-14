import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Rootfs settings ab Gradle BuildConfig mein jaati hain. Priority:
 *   1. Environment variable (GitHub Actions: ROOTFS_URL / ROOTFS_SHA256 repo variables)
 *   2. Gradle property (-ProotfsUrl=... / -ProotfsSha256=...)
 *   3. Default placeholder (Config.kt mein dikhta hai)
 * Isse CI mein Config.kt ko sed se edit karne ki zaroorat nahi rehti.
 */
fun setting(envName: String, propName: String, default: String): String =
    System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: (findProperty(propName) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: default

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val rootfsUrl = setting(
    "ROOTFS_URL", "rootfsUrl",
    "https://example.com/rootfs/debian-arm64.tar.xz"
)
val rootfsSha256 = setting(
    "ROOTFS_SHA256", "rootfsSha256",
    "0000000000000000000000000000000000000000000000000000000000000000"
).lowercase(Locale.ROOT)

if (!Regex("[0-9a-f]{64}").matches(rootfsSha256)) {
    throw GradleException("ROOTFS_SHA256 must be exactly 64 hex characters, got length ${rootfsSha256.length}")
}

android {
    namespace = "com.wani.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wani.terminal"
        minSdk = 26
        // Android 10+ app data se binaries chalane ki restriction ki wajah se 28 rakha gaya hai (Termux bhi yahi karta hai)
        targetSdk = 28
        versionCode = 3
        versionName = "1.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        buildConfigField("String", "ROOTFS_URL", rootfsUrl.asBuildConfigString())
        buildConfigField("String", "ROOTFS_SHA256", rootfsSha256.asBuildConfigString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            // libproot.so ko nativeLibraryDir mein extract karna zaroori hai
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
