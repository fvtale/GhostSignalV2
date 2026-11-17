import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

group = "com.ghostsignal"
version = "1.0"

/**
 * The game engine, for two front-ends: the JVM build is what the Android app runs; the JS build is
 * an ES module the web player at /play runs in the browser (see src/jsMain and buildWebEngine below).
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

/**
 * The web player's copy of the engine, in server/play/engine. Rebuild it after any change under
 * core/, on any OS, and commit it with the change; never copy or edit it by hand:
 *
 *   ./gradlew -p core buildWebEngine          (Windows: gradlew.bat -p core buildWebEngine)
 *
 * CI (tools/check_web_engine.sh) checks the committed copy against a fresh build.
 */
val buildWebEngine by tasks.registering {
  group = "build"
  description = "Builds the JS engine and copies it to server/play/engine for the web player."
  dependsOn("jsProductionExecutableCompileSync")
  val out = layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")
  val dest = projectDir.resolve("../server/play/engine")
  doLast {
    dest.mkdirs()
    val module = out.get().file("ghostsignal-core.mjs").asFile.readText()
    // The source map stays out of the repo, so drop the comment that points at it. Lines are
    // joined with \n whatever the OS, so the file is byte-for-byte the same from every machine.
    val kept = module.lineSequence().filterNot { it.startsWith("//# sourceMappingURL=") }.joinToString("\n")
    dest.resolve("ghostsignal-core.mjs").writeText(kept)
    val types = out.get().file("ghostsignal-core.d.mts").asFile.readText()
    dest.resolve("ghostsignal-core.d.mts").writeText(types.lineSequence().joinToString("\n"))
    println("server/play/engine/ghostsignal-core.mjs: ${dest.resolve("ghostsignal-core.mjs").length()} bytes")
  }
}
