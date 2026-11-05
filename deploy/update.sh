#!/usr/bin/env bash
# Bring GhostSignal V2 on this host up to the latest commit of the branch it follows, and only
# keep it if it works.
#
#   sudo /opt/ghostsignal/deploy/update.sh           # update if the branch moved
#   sudo /opt/ghostsignal/deploy/update.sh --force   # re-run the checks anyway
#
# The same order as DataRail Storage's update.sh on this VPS: pull, install anything new, run
# the server's tests, restart, then ask the running service if it is healthy. A failure at the
# tests or the health check puts the previous commit back and restarts on it, so a bad push
# costs a few seconds of the old version rather than an outage.

set -euo pipefail

APP=/opt/ghostsignal
SERVICE=ghostsignal
HEALTH=http://127.0.0.1:8090/health
LOG=/tmp/ghostsignal-update-tests.log

[ "$(id -u)" -eq 0 ] || { echo "run this as root (sudo)" >&2; exit 1; }
cd "$APP"

# Git and npm run as the service user, so nothing in the checkout ends up owned by root.
as_app() { sudo -u ghostsignal -H "$@"; }
deps() { (cd server && as_app npm ci --omit=dev --no-audit --no-fund --loglevel=error); }

branch=$(as_app git rev-parse --abbrev-ref HEAD)
before=$(as_app git rev-parse HEAD)
as_app git fetch -q origin "$branch"
target=$(as_app git rev-parse "origin/$branch")

if [ "$before" = "$target" ] && [ "${1:-}" != "--force" ]; then
    echo "Already up to date: $(as_app git log --oneline -1)"
    exit 0
fi

rollback() {
    echo "!! $1 -- going back to ${before:0:7}" >&2
    as_app git reset -q --hard "$before"
    as_app git diff --quiet "$before" "$target" -- server/package-lock.json || deps || true
    systemctl restart "$SERVICE"
    exit 1
}

as_app git merge -q --ff-only "$target" || rollback "$branch has diverged from this checkout"
echo "Now at: $(as_app git log --oneline -1) ($branch)"

if ! as_app git diff --quiet "$before" "$target" -- server/package-lock.json || [ ! -d server/node_modules ]; then
    deps || rollback "dependency install failed"
fi

(cd server && as_app npm test >"$LOG" 2>&1) || { tail -30 "$LOG" >&2; rollback "tests failed"; }
echo "Tests: $(grep -E '^# (pass|fail) ' "$LOG" | tr '\n' ' ')"

# Host config that lives in the repo: pick it up when it changed.
if ! cmp -s deploy/ghostsignal.service /etc/systemd/system/$SERVICE.service; then
    install -m 644 deploy/ghostsignal.service /etc/systemd/system/$SERVICE.service
    systemctl daemon-reload
    echo "Service unit updated."
fi
# Once install.sh has put the site in place (it waits for DNS), keep it current.
if [ -f /etc/caddy/sites.d/ghostsignal.caddy ]; then
    deploy/caddy-site.sh || rollback "Caddy rejected deploy/ghostsignal.caddy"
fi

systemctl restart "$SERVICE"
for _ in $(seq 1 20); do
    if curl -fsS "$HEALTH" >/dev/null 2>&1; then
        echo "Healthy: $(curl -fsS "$HEALTH")"
        exit 0
    fi
    sleep 1
done
journalctl -u "$SERVICE" -n 20 --no-pager >&2 || true
rollback "service did not come back healthy"
