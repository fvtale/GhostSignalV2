# Deploying

Production runs on the DataRail VPS, next to DataRail Storage and DataRail Trends, and serves
**https://ghostsignal.datarail.org** through the Caddy already on that host. Accounts, the
admin page, the account page, the web player and the character and voice services all come
from that one origin. The app talks to it by default.

V2 took over the original GhostSignal's place on that host, so on the server it keeps the
original's names: the `ghostsignal` user, unit, folders, port and site. The original no longer
runs there.

- [The live server](#the-live-server)
- [First time](#first-time)
- [Updating](#updating)
- [Keys, payments and event phones](#keys-payments-and-event-phones)
- [Sharing Caddy with DataRail Storage](#sharing-caddy-with-datarail-storage)
- [Backups](#backups)
- [Somewhere else](#somewhere-else)

## The live server

| | |
| --- | --- |
| Host | The InterServer VPS at `162.35.99.105` that runs DataRail Storage (its `DEPLOY.md` covers the machine) |
| Name | `ghostsignal.datarail.org`, an A record in the IONOS DNS for datarail.org |
| Code | `/opt/ghostsignal`, a clone of this repository on the branch it follows (`main`), owned by the `ghostsignal` user |
| Data | `/srv/ghostsignal/data`, mode 700: accounts, sessions, glasses links, routes, soundtracks, the voice cache |
| Secrets | `/etc/ghostsignal.env`, root-only: tokens, API keys, webhook secrets |
| Service | systemd unit `ghostsignal` (`deploy/ghostsignal.service`): Node 22 on `127.0.0.1:8090` |
| HTTPS | Caddy, with the site in `/etc/caddy/sites.d/ghostsignal.caddy` (from `deploy/ghostsignal.caddy`) |
| Repository | Read-only deploy key in `/home/ghostsignal/.ssh/github_deploy` |
| Neighbours | DataRail Storage (port 8080) and DataRail Trends (port 8790, served under `storage.datarail.org/trends`) |

The server binds to loopback only; Caddy is the way in, and passes each player's address along
(`TRUST_PROXY`) so the per-address limits on sign-ups, sign-ins, characters and voices apply to
each player rather than to Caddy.

## First time

The live server is set up already; this is for a new host or a rebuild.

**1. DNS.** In the IONOS DNS for datarail.org, add an A record `ghostsignal` pointing at
`162.35.99.105`.

**2. Run the installer** from the computer with the VPS key. Download
[`deploy/install.sh`](https://github.com/fvtale/GhostSignalV2/blob/main/deploy/install.sh)
(**Download raw file**), open a terminal (PowerShell on Windows, Terminal on a Mac) in the folder
it's in, copy it to the server, and run it there:

```bash
scp -i $HOME/.ssh/datarail_storage_vps install.sh root@162.35.99.105:/root/ghostsignal-install.sh
ssh -i $HOME/.ssh/datarail_storage_vps root@162.35.99.105 "bash /root/ghostsignal-install.sh --admin you@example.com"
```

The server follows `main`. (`--branch NAME` makes it follow another branch; running the
installer again with a different `--branch` moves a running server over.)

The first run stops at the deploy key: it prints a public key (one line starting `ssh-ed25519`). Add it at
[Settings → Deploy keys](https://github.com/fvtale/GhostSignalV2/settings/keys/new) with **Allow
write access** unticked, then run the same command again. It carries on from where it stopped:

1. Node.js 22 (NodeSource), the `ghostsignal` user and its folders
2. the checkout, its dependencies, and the server's test suite, run on the server itself
3. `/etc/ghostsignal.env` with a fresh `EVENT_TOKEN` and `OPERATOR_TOKEN`, generated there
4. the service, started and health-checked on `127.0.0.1:8090`
5. your admin account, made while the server is reachable from the VPS only, so nobody can sign up
   with the admin email first. Its password is printed once.
6. the Caddy site, once the DNS name resolves to the VPS (if it doesn't yet, it says so and
   stops; run it again later). Caddy gets the certificate on its own.

Every step checks what's already there, so running it again is safe.

**3. Sign in** at https://ghostsignal.datarail.org/account/ with the printed password and change
it. Then https://ghostsignal.datarail.org/admin/.

**4. Keys** for the characters and voices. See [below](#keys-payments-and-event-phones).

## Updating

Push to `main` (CI runs as usual, and publishes the new APK), then:

```bash
ssh -i ~/.ssh/datarail_storage_vps root@162.35.99.105 /opt/ghostsignal/deploy/update.sh
```

This works like DataRail Storage's update:
1. It fetches the branch and runs `npm ci` if the lockfile changed.
2. It runs the server's tests on the server.
3. It picks up changes to the systemd unit or the Caddy site.
4. It restarts the service and checks its health.

If the tests or the health check fail, it puts the previous commit back and restarts on that.
If nothing is new, it says so and stops. `--force` re-runs the checks anyway.

**Once, while the server's `update.sh` still says `APP=/opt/ghostsignalv2`** (3e77dd3 and the
commits before this fix). Those scripts described a `ghostsignalv2` layout that was never
deployed, so that copy fails at its first `cd`. Run the fixed one from the fetched branch
instead. It updates the checkout, and the command above works from then on:

```bash
ssh -i ~/.ssh/datarail_storage_vps root@162.35.99.105 'sudo -u ghostsignal -H git -C /opt/ghostsignal fetch -q origin main && sudo -u ghostsignal -H git -C /opt/ghostsignal show origin/main:deploy/update.sh >/root/ghostsignal-update.sh && bash /root/ghostsignal-update.sh'
```

## Keys, payments and event phones

Edit `/etc/ghostsignal.env` on the server (as root; `server/.env.example` explains every
variable), then `systemctl restart ghostsignal`:

- `MODEL_API_KEY` (Meta Model API): live character replies (Muse Spark) and runner speech
  transcription (Muse Voice). Without it, characters use their written fallback lines and
  speech falls back to the on-device recognizers. Character voices are always rendered on
  the device (Android TTS, browser speech synthesis); the server keeps no voice keys.
- Payments: paste the payment link on the admin page (Players tab), or set `PAYMENT_LINK_URL`.
  For premium to switch on by itself, point the provider's webhook at
  `https://ghostsignal.datarail.org/v1/billing/stripe` (or `/v1/billing/lemonsqueezy`) and put its
  signing secret in `STRIPE_WEBHOOK_SECRET` (or `LEMONSQUEEZY_WEBHOOK_SECRET`).

Players who sign in use the characters and voices on their account, within per-account limits.
Phones built for an event can skip accounts by carrying the event token. Copy it from the server
(`grep EVENT_TOKEN /etc/ghostsignal.env`) into `local.properties` as `ghostsignal.eventToken`
before building. App builds talk to `https://ghostsignal.datarail.org` unless
`ghostsignal.backendUrl` says otherwise. The phone's operator screen can change both.

## Sharing Caddy with DataRail Storage

Caddy on the VPS belongs to DataRail Storage: its update copies its `deploy/Caddyfile` over
`/etc/caddy/Caddyfile` whenever the two differ. Other apps keep their sites in
`/etc/caddy/sites.d/`, which that Caddyfile imports. `deploy/caddy-site.sh` (run by the
installer and by every update):
- copies GhostSignal V2's site there;
- adds the import line if the Caddyfile doesn't have it yet;
- validates the whole configuration and reloads Caddy;
- puts both files back if validation fails.

Storage's own `deploy/Caddyfile` carries the same import line (on its `main` since Oct 24), so
a storage update keeps GhostSignal V2's site. If that line is ever taken out, the next storage
update drops the site and GhostSignal V2 stops answering on HTTPS until GhostSignal V2's `update.sh`
or `caddy-site.sh` runs again.

## Backups

Everything that matters is in `/srv/ghostsignal/data`. The code and the settings can be rebuilt;
the accounts can't. From your machine:

```bash
ssh -i ~/.ssh/datarail_storage_vps root@162.35.99.105 'tar -C /srv/ghostsignal -czf - data' \
    > ghostsignal-data-$(date +%F).tgz
```

To restore:
1. `systemctl stop ghostsignal`
2. Unpack it over `/srv/ghostsignal/data`.
3. `chown -R ghostsignal: /srv/ghostsignal/data`
4. Start the service again.

## Somewhere else

The server is plain Node 22 with one dependency, so any Linux host works the same way: edit the
domain in `deploy/ghostsignal.caddy` and the paths at the top of the scripts. For a container
platform, build `server/Dockerfile` from the repository root and mount a volume at `/data`. Set
`TRUST_PROXY=1` only when a proxy on the same machine is the one way in. Behind a platform's
load balancer, leave it unset: the platform's own address limits apply.
