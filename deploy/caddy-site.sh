#!/usr/bin/env bash
# Put GhostSignal V2's site into the VPS's Caddy, and only keep the change if Caddy accepts it.
#
#   sudo /opt/ghostsignal/deploy/caddy-site.sh
#
# Caddy on this host belongs to DataRail Storage: its deploy/Caddyfile is /etc/caddy/Caddyfile.
# Other apps add sites through files in /etc/caddy/sites.d/, which that Caddyfile imports. This
# copies deploy/ghostsignal.caddy there, adds the import if the Caddyfile doesn't have it yet,
# validates the whole configuration, and reloads Caddy. If validation fails, both files go back
# to what they were. Run by install.sh and update.sh; safe to run any time.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MAIN=/etc/caddy/Caddyfile
SITES=/etc/caddy/sites.d
SITE=$SITES/ghostsignal.caddy
IMPORT="import $SITES/*.caddy"

[ "$(id -u)" -eq 0 ] || { echo "run this as root (sudo)" >&2; exit 1; }
command -v caddy >/dev/null || { echo "Caddy isn't installed on this host (DataRail Storage's DEPLOY.md, step 6)." >&2; exit 1; }
[ -f "$MAIN" ] || { echo "$MAIN is missing." >&2; exit 1; }

has_import() { grep -qE "^[[:space:]]*import[[:space:]]+$SITES/\*\.caddy[[:space:]]*$" "$MAIN"; }

if cmp -s "$HERE/ghostsignal.caddy" "$SITE" && has_import; then
    echo "Caddy already serves GhostSignal V2's site."
    exit 0
fi

backup=$(mktemp -d)
trap 'rm -rf "$backup"' EXIT
cp -a "$MAIN" "$backup/Caddyfile"
[ -f "$SITE" ] && cp -a "$SITE" "$backup/site"

install -d -m 755 "$SITES"
install -m 644 "$HERE/ghostsignal.caddy" "$SITE"
if ! has_import; then
    printf '\n# Sites of the other apps on this host, one file each (GhostSignal V2: /opt/ghostsignal/deploy).\n%s\n' "$IMPORT" >>"$MAIN"
    cat >&2 <<EOF
Added "$IMPORT" to $MAIN.
DataRail Storage's update.sh copies its deploy/Caddyfile over that file whenever the two
differ, so that Caddyfile needs the same line, or the next storage update takes GhostSignal V2
offline. See GhostSignal V2's DEPLOY.md, "Sharing Caddy with DataRail Storage".
EOF
fi

if ! out=$(caddy validate --config "$MAIN" --adapter caddyfile 2>&1); then
    cp -a "$backup/Caddyfile" "$MAIN"
    if [ -f "$backup/site" ]; then cp -a "$backup/site" "$SITE"; else rm -f "$SITE"; fi
    echo "$out" | tail -5 >&2
    echo "!! Caddy rejected the configuration; put the previous one back." >&2
    exit 1
fi
systemctl reload caddy
echo "Caddy now serves $(awk 'NF && $1 !~ /^#/ && /\{/ {print $1; exit}' "$SITE")."
