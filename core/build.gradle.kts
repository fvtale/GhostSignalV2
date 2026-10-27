import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

group = "com.ghostsignal"
version = "1.0"

/**
 * The game engine, for two front-ends: the JVM build is what the Android app runs; the JS build is
 * an ES module the web player at /play runs in the browser (see src/jsMain and tools/build_web_engine.sh).
 */
kotlin {
  jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

  js {
    outputModuleName = "ghostsignal-core"
    // Only compiled to a library here; the server serves the module and its own scripts load it.
    nodejs()
    binaries.executable()
    useEsModules()
    generateTypeScriptDefinitions()
  }

  sourceSets {
    commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    jvmTest.dependencies {
      implementation(kotlin("test-junit"))
      implementation(libs.junit)
    }
  }
}

tasks.named<Test>("jvmTest") {
  // The mission file ships inside the Android app; the core tests validate it.
  systemProperty("ghostsignal.missionPath", rootDir.resolve("../app/src/main/assets/mission/ghostsignal.json").absolutePath)
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
