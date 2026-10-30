#!/usr/bin/env bash
# Compiles the game engine (core/) to the single ES module the web player loads, and copies it to
# server/play/engine/. Run it after changing anything in core/; CI checks the copy is current.
# The work is the Gradle task buildWebEngine (core/build.gradle.kts), which also runs on Windows:
#   gradlew.bat -p core buildWebEngine
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -p core -q buildWebEngine
