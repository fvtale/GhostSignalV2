#!/usr/bin/env bash
# CI: rebuilds the web player's engine and checks it matches the committed copy. The Kotlin/JS
# compiler doesn't fix the order in which it lists a class's interfaces, or the order of the
# polyfills at the top of the file, so both are sorted on both sides before comparing; anything
# else that differs fails the check.
set -euo pipefail
cd "$(dirname "$0")/.."
committed=$(mktemp)
git show HEAD:server/play/engine/ghostsignal-core.mjs > "$committed"
tools/build_web_engine.sh
normalize() {
  # initMetadataForClass(Name, 'Name', ctor, Parent, [I1, I2], ...): sort the [...] lists.
  perl -pe 's/^(initMetadataFor\w+\(.*?)\[([^\[\]]*)\]/$1 . "[" . join(", ", sort split(m{, }, $2)) . "]"/e' "$1" |
    # The polyfills region: one top-level statement per polyfill, each starting in column 0 and
    # running to its closing brace. Sort them whole; if the region doesn't split cleanly, leave it.
    perl -0pe 's{(//region block: polyfills\n)(.*?)(?=//endregion)}{
      my ($head, $body) = ($1, $2);
      my @blocks = $body =~ m/^(?!\})\S.*\n(?:[ \t\}].*\n)*/mg;
      $head . (join("", @blocks) eq $body ? join("", sort @blocks) : $body)
    }se'
}
if diff <(normalize "$committed") <(normalize server/play/engine/ghostsignal-core.mjs) > /dev/null; then
  git checkout -- server/play/engine/ghostsignal-core.mjs
  echo "web engine matches the Kotlin source"
else
  diff <(normalize "$committed") <(normalize server/play/engine/ghostsignal-core.mjs) | head -40
  echo "::error::The web player's engine is out of date: run tools/build_web_engine.sh and commit server/play/engine"
  exit 1
fi
