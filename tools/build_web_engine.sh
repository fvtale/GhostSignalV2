#!/usr/bin/env bash
# Compiles the game engine (core/) to the single ES module the web player loads, and copies it to
# server/play/engine/. Run it after changing anything in core/; CI checks the copy is current.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -p core -q jsProductionExecutableCompileSync
out=core/build/compileSync/js/main/productionExecutable/kotlin
mkdir -p server/play/engine
# The source map stays out of the repo, so drop the comment that points at it.
sed '/^\/\/# sourceMappingURL=/d' "$out/ghostsignal-core.mjs" > server/play/engine/ghostsignal-core.mjs
cp "$out/ghostsignal-core.d.mts" server/play/engine/ghostsignal-core.d.mts
echo "server/play/engine/ghostsignal-core.mjs: $(wc -c < server/play/engine/ghostsignal-core.mjs) bytes"
