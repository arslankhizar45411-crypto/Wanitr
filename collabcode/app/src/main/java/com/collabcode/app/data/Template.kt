package com.collabcode.app.data

/**
 * Turns the editor's files into a buildable Android project.
 * User .kt files go to src/main/kotlin/ (the standard Gradle layout, no custom source sets).
 * Other files (html, md) stay at the repo root. Generated files sit next to them.
 */
object Template {
    private val PKG_LINE = Regex("^\\s*package\\s+[\\w.]+", RegexOption.MULTILINE)

    /** Local file names the user may not create, because the build generates them. */
    val RESERVED = setOf(
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        "AndroidManifest.xml",
        "MainActivity.kt",
    )

    /** Repo paths owned by the build. A normal commit never deletes these. */
    val GENERATED_PATHS = setOf(
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        "src/main/AndroidManifest.xml",
        "src/main/kotlin/MainActivity.kt",
        ".github/workflows/build.yml",
    )

    fun packageOf(mainKt: String): String =
        PKG_LINE.find(mainKt)?.value?.trim()?.removePrefix("package")?.trim() ?: "com.example.app"

    /** Maps local file names to repo paths. Adds a package line to .kt files that lack one. */
    fun userFiles(files: Map<String, String>, pkg: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, text) in files) {
            if (name.endsWith(".kt")) {
                val body = if (PKG_LINE.containsMatchIn(text)) text else "package $pkg\n\n$text"
                out["src/main/kotlin/$name"] = body
            } else {
                out[name] = text
            }
        }
        return out
    }

    fun generated(pkg: String): Map<String, String> = linkedMapOf(
        "settings.gradle.kts" to SETTINGS,
        "build.gradle.kts" to rootBuild(pkg),
        "gradle.properties" to GRADLE_PROPS,
        "src/main/AndroidManifest.xml" to MANIFEST,
        "src/main/kotlin/MainActivity.kt" to mainActivity(pkg),
        ".github/workflows/build.yml" to BUILD_WORKFLOW,
    )

    private val SETTINGS = """
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "CollabApp"
""".trimStart()

    private val GRADLE_PROPS = """
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
""".trimStart()

    private val MANIFEST = """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="CollabApp"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart()

    private fun rootBuild(pkg: String) = """
plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "$pkg"
    compileSdk = 35

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
""".trimStart()

    // Runs the user's main() and shows whatever it prints.
    private fun mainActivity(pkg: String) = """
package $pkg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        try {
            main()
        } catch (e: Throwable) {
            print("Error: " + e.toString())
        } finally {
            System.setOut(original)
        }
        val output = buffer.toString("UTF-8")
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text(output)
                }
            }
        }
    }
}
""".trimStart()
}
