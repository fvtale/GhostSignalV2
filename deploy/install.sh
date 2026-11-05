#!/usr/bin/env bash
# Set GhostSignal V2 up on the VPS that runs DataRail Storage, or finish a setup that stopped part
# way. Safe to run again: every step checks what is already there. Run it as root. Copy it to
# the server and run it there (the same two commands work in PowerShell, Terminal and bash):
#
#   scp -i $HOME/.ssh/datarail_storage_vps install.sh root@162.35.99.105:/root/ghostsignal-install.sh
#   ssh -i $HOME/.ssh/datarail_storage_vps root@162.35.99.105 "bash /root/ghostsignal-install.sh --admin you@example.com"
#
# or on the server, once the checkout exists:  sudo /opt/ghostsignal/deploy/install.sh
#
#   --admin EMAIL       make this the first admin account, before the site is public
#   --branch NAME       the branch this host follows (default: the repository's default branch)
#   --skip-dns-check    put the Caddy site in place without checking that DNS points here
#
# In order: Node.js 22, a ghostsignal user, a read-only deploy key, the checkout, the server's
# tests, /etc/ghostsignal.env with fresh tokens, the systemd service on 127.0.0.1:8090, the
# first admin account, and last the Caddy site, once ghostsignal.datarail.org points here.
# See DEPLOY.md.

main() {
    set -euo pipefail
    # Read over ssh as a script: nothing below may read the rest of it as its own input.
    exec </dev/null
    # The service user runs git and npm below and can't read root's home.
    cd /

    local repo=${GHOSTSIGNAL_REPO:-git@github.com:fvtale/GhostSignalV2.git}
    local app=/opt/ghostsignal data=/srv/ghostsignal/data env_file=/etc/ghostsignal.env
    local health=http://127.0.0.1:8090
    local admin="" branch="" dns_check=1
    while [ $# -gt 0 ]; do
        case "$1" in
            --admin) admin=${2:?--admin needs an email}; shift 2 ;;
            --branch) branch=${2:?--branch needs a name}; shift 2 ;;
            --skip-dns-check) dns_check=0; shift ;;
            *) echo "unknown option: $1 (see the top of deploy/install.sh)" >&2; exit 2 ;;
        esac
    done
    [ "$(id -u)" -eq 0 ] || { echo "run this as root" >&2; exit 1; }
    local email_re='^[^@[:space:],|&]+@[^@[:space:],|&]+\.[^@[:space:],|&]+$'
    if [ -n "$admin" ] && ! [[ $admin =~ $email_re ]]; then
        echo "--admin: \"$admin\" doesn't look like an email address" >&2
        exit 2
    fi

    step() { printf '\n== %s\n' "$*"; }
    as_app() { sudo -u ghostsignal -H "$@"; }
    token() { node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("base64url"))'; }

    step "Node.js 22"
    export DEBIAN_FRONTEND=noninteractive
    local have
    have=$(/usr/bin/node -v 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/' || true)
    if [ "${have:-0}" -ge 22 ]; then
        echo "node $(/usr/bin/node -v)"
    else
        apt-get update -q
        apt-get install -yq ca-certificates curl gnupg git
        install -d -m 755 /etc/apt/keyrings
        curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
        echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" \
            >/etc/apt/sources.list.d/nodesource.list
        apt-get update -q
        apt-get install -yq nodejs
        echo "node $(/usr/bin/node -v)"
    fi
    command -v git >/dev/null || apt-get install -yq git

    step "User and folders"
    id -u ghostsignal >/dev/null 2>&1 ||
        useradd --system --create-home --home-dir /home/ghostsignal --shell /usr/sbin/nologin ghostsignal
    install -d -o ghostsignal -g ghostsignal -m 755 "$app"
    install -d -o ghostsignal -g ghostsignal -m 700 /srv/ghostsignal "$data"
    echo "code $app · data $data"

    step "Read-only access to the repository"
    local ssh_dir=/home/ghostsignal/.ssh
    local key=$ssh_dir/github_deploy
    # GitHub's published ed25519 host key (SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU),
    # pinned rather than taken on trust from ssh-keyscan.
    local github_key="github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
    install -d -o ghostsignal -g ghostsignal -m 700 "$ssh_dir"
    [ -f "$key" ] || as_app ssh-keygen -q -t ed25519 -N "" -C "ghostsignal@$(hostname)" -f "$key"
    grep -qxF "$github_key" "$ssh_dir/known_hosts" 2>/dev/null || echo "$github_key" | as_app tee -a "$ssh_dir/known_hosts" >/dev/null
    local git_ssh="ssh -i $key -o IdentitiesOnly=yes"
    if [[ $repo == git@* ]] && ! as_app env GIT_SSH_COMMAND="$git_ssh" git ls-remote "$repo" HEAD >/dev/null 2>&1; then
        cat <<EOF
This server can't read the repository yet. Add its key as a deploy key, read-only:

  https://github.com/fvtale/GhostSignalV2/settings/keys/new
  Title: $(hostname) VPS
  Allow write access: leave it unticked
  Key:

$(cat "$key.pub")

Then run this again.
EOF
        exit 3
    fi
    echo "ok"

    step "Code"
    if [ ! -d "$app/.git" ]; then
        as_app env GIT_SSH_COMMAND="$git_ssh" git clone -q ${branch:+--branch "$branch"} "$repo" "$app"
        as_app git -C "$app" config core.sshCommand "$git_ssh"
    elif [ -n "$branch" ] && [ "$(as_app git -C "$app" rev-parse --abbrev-ref HEAD)" != "$branch" ]; then
        as_app git -C "$app" fetch -q origin "$branch"
        as_app git -C "$app" checkout -q -B "$branch" "origin/$branch"
    fi
    echo "$(as_app git -C "$app" rev-parse --abbrev-ref HEAD): $(as_app git -C "$app" log --oneline -1)"

    step "Dependencies and the server's tests"
    (cd "$app/server" && as_app npm ci --omit=dev --no-audit --no-fund --loglevel=error)
    local log=/tmp/ghostsignal-install-tests.log
    (cd "$app/server" && as_app npm test >"$log" 2>&1) || { tail -30 "$log" >&2; echo "!! the tests failed; nothing is running yet" >&2; exit 1; }
    echo "$(grep -E '^# (pass|fail) ' "$log" | tr '\n' ' ')"

    step "Settings and secrets: $env_file"
    if [ ! -f "$env_file" ]; then
        (
            umask 077
            cat >"$env_file" <<EOF
# GhostSignal V2's secrets and settings, read by the ghostsignal service. Root-only.
# Every variable is explained in server/.env.example. After a change:
#   systemctl restart ghostsignal

# App builds made for an event carry EVENT_TOKEN (local.properties: ghostsignal.eventToken);
# signed-in players don't need it. OPERATOR_TOKEN signs in to /admin when nothing else can.
EVENT_TOKEN=$(token)
OPERATOR_TOKEN=$(token)
ADMIN_EMAILS=$admin

# Character replies and speech transcription stay off until this is set.
MODEL_API_KEY=

# Premium. The link and price can also be set on /admin, Players tab.
PAYMENT_LINK_URL=
PREMIUM_PRICE_LABEL=
MANAGE_BILLING_URL=
STRIPE_WEBHOOK_SECRET=
LEMONSQUEEZY_WEBHOOK_SECRET=
EOF
        )
        echo "Wrote it, with a fresh EVENT_TOKEN and OPERATOR_TOKEN."
    else
        echo "Keeping the one that's there."
        if [ -n "$admin" ]; then
            local current
            current=$(sed -n 's/^ADMIN_EMAILS=//p' "$env_file" | tail -1)
            case ",$current," in
                *",$admin,"*) ;;
                *)
                    if grep -q '^ADMIN_EMAILS=' "$env_file"; then
                        sed -i "s|^ADMIN_EMAILS=.*|ADMIN_EMAILS=${current:+$current,}$admin|" "$env_file"
                    else
                        echo "ADMIN_EMAILS=$admin" >>"$env_file"
                    fi
                    echo "Added $admin to ADMIN_EMAILS."
                    ;;
            esac
        fi
    fi

    step "Service"
    if ! cmp -s "$app/deploy/ghostsignal.service" /etc/systemd/system/ghostsignal.service; then
        install -m 644 "$app/deploy/ghostsignal.service" /etc/systemd/system/ghostsignal.service
        systemctl daemon-reload
    fi
    systemctl enable -q ghostsignal
    systemctl restart ghostsignal
    local up=0
    for _ in $(seq 1 20); do
        if [[ $(curl -fsS "$health/health" 2>/dev/null) == *'"ok":true'* ]]; then up=1; break; fi
        sleep 1
    done
    if [ "$up" -ne 1 ]; then
        journalctl -u ghostsignal -n 30 --no-pager >&2 || true
        echo "!! the service didn't come up on $health" >&2
        exit 1
    fi
    echo "Running: $(curl -fsS "$health/health")"

    # Only this machine can reach the server until Caddy is set up below, so nobody else can
    # claim the admin email first (any account with an ADMIN_EMAILS address is an admin).
    if [ -n "$admin" ]; then
        step "Admin account: $admin"
        local pw body out code
        pw=$(token | cut -c1-24)
        body=$(node -e 'process.stdout.write(JSON.stringify({ email: process.argv[1], password: process.argv[2], client: "web" }))' "$admin" "$pw")
        out=$(mktemp)
        code=$(curl -s -o "$out" -w '%{http_code}' -H 'content-type: application/json' --data-binary "$body" "$health/v1/account/signup")
        case "$code" in
            201)
                local t
                t=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).token)' "$out")
                curl -s -o /dev/null -X POST -H "x-account-token: $t" "$health/v1/account/logout" || true
                echo "Created it, as an admin. Its first password, shown only now:"
                echo
                echo "    $pw"
                echo
                echo "Sign in on /account and change it."
                ;;
            409) echo "There's already an account with that email; it's an admin through ADMIN_EMAILS." ;;
            *) echo "!! couldn't create it (HTTP $code): $(cat "$out")" >&2; rm -f "$out"; exit 1 ;;
        esac
        rm -f "$out"
    fi

    step "HTTPS through Caddy"
    local domain there
    domain=$(awk 'NF && $1 !~ /^#/ && /\{/ {print $1; exit}' "$app/deploy/ghostsignal.caddy")
    there=$(getent ahostsv4 "$domain" | awk 'NR == 1 {print $1}' || true)
    local mine
    mine=" $(hostname -I) "
    if [ "$dns_check" -eq 1 ] && [[ $mine != *" ${there:-none} "* ]]; then
        cat <<EOF
$domain ${there:+points at $there, }not at this server ($(hostname -I | awk '{print $1}')).
Add an A record for "${domain%%.*}" pointing at this server's address in the DNS for
${domain#*.}, wait until it resolves here, then run this again. (Asking for a certificate
before then would count failed attempts against Let's Encrypt's limits.)

Everything else is in place: the server is running on $health for this machine only.
EOF
        exit 0
    fi
    "$app/deploy/caddy-site.sh"
    local ok=0
    for _ in $(seq 1 30); do
        if [[ $(curl -fsS "https://$domain/health" 2>/dev/null) == *'"ok":true'* ]]; then ok=1; break; fi
        sleep 2
    done
    [ "$ok" -eq 1 ] || echo "!! https://$domain/health isn't answering yet; Caddy may still be getting the certificate (journalctl -u caddy -n 30)." >&2

    step "Done"
    cat <<EOF
GhostSignal V2: https://$domain
  Admin     https://$domain/admin/
  Accounts  https://$domain/account/
  Play      https://$domain/play/

Updates from now on:  sudo $app/deploy/update.sh
EOF
    grep -q '^MODEL_API_KEY=.' "$env_file" ||
        echo "Characters and speech are off until MODEL_API_KEY is in $env_file (then: systemctl restart ghostsignal)."
}

main "$@"
