import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

/**
 * local.properties first, then -P gradle properties, then environment (dots become underscores).
 * Blank counts as unset: CI passes an empty value for a repository variable that isn't there.
 */
fun setting(name: String, default: String = ""): String =
    listOf(
            localProperties.getProperty(name),
            providers.gradleProperty(name).orNull,
            System.getenv(name.uppercase().replace('.', '_')),
        )
        .firstOrNull { !it.isNullOrBlank() } ?: default

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// FMOD is optional. Unpack the FMOD Engine Android SDK into third_party/fmod (see docs/AUDIO.md);
// without it the app uses its built-in binaural renderer.
val fmodDir = rootDir.resolve("third_party/fmod")
val fmodEnabled = fmodDir.resolve("api/core/inc/fmod.hpp").exists()

android {
  namespace = "com.ghostsignal.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.ghostsignal.v2"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Meta Wearables Device Access Toolkit. With Developer Mode on in the Meta AI app these can
    // stay 0; for a registered app use the values from Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] = setting("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] = setting("mwdat_client_token", "0")

    // The live server (deploy/, DEPLOY.md). Settings on the phone can point elsewhere.
    buildConfigField("String", "BACKEND_URL", quoted(setting("ghostsignal.backendUrl", "https://ghostsignalv2.datarail.org")))
    buildConfigField("String", "EVENT_TOKEN", quoted(setting("ghostsignal.eventToken")))
    buildConfigField("boolean", "FMOD_ENABLED", fmodEnabled.toString())

    if (fmodEnabled) {
      ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
      externalNativeBuild { cmake { arguments += "-DFMOD_DIR=${fmodDir.absolutePath}" } }
    }
  }

  if (fmodEnabled) {
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    sourceSets["main"].jniLibs.srcDirs(fmodDir.resolve("api/core/lib"), fmodDir.resolve("plugins"))
  }

  // Every build, on CI or on any machine, signs with this one key, so a newer APK installs over an
  // older one on the phone instead of failing with "App not installed". It's a debug key and not a
  // secret; a Play Store release would get its own key, kept out of the repository.
  signingConfigs {
    getByName("debug") {
      storeFile = file("sideload-debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  lint {
    // Experimental DAT capabilities are fine for sideloaded event builds.
    abortOnError = false
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation("com.ghostsignal:core")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.play.services.location)
  implementation(libs.okhttp)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.inputs)
  implementation(libs.mwdat.motion)
  implementation(libs.mwdat.speech)
  implementation(libs.mwdat.mockdevice)
  if (fmodEnabled) implementation(files(fmodDir.resolve("api/core/lib/fmod.jar")))
  testImplementation(libs.junit)
}
