# GhostSignal V2 — post-hackathon rewrite plan

Status: approved direction (2026-11-03). Owner decisions locked:

- **Scope:** all V1-origin surfaces — `core/`, `server/`, web player, Android app.
- **Approach:** new commits on `main`. The V1 starting point (`09128e8`, copied from
  GhostSignal `881186c`) stays in history and is disclosed as the pre-hackathon skeleton.
  The rewrite is landed as fresh, original implementation on top of it so hackathon
  judges can diff `09128e8..HEAD` and see the post-start-date work. History is NOT
  rewritten.
- **Aesthetic:** datarail.org/library "rail meets circuitry" across web player,
  account, admin, and the Android theme.

## Why a rewrite

GhostSignalV1 was a rough skeleton started shortly before the hackathon. To submit
original code made after the hack start date, every V1-origin file is re-implemented
from scratch in V2: fresh structure, fresh naming where the API allows, fresh comments,
same behaviour contracts (existing tests must stay green). Files authored after the
fork point (Meta stack, spatial-audio elevation, agent router) are kept and polished,
not rewritten.

## Design language — rail meets circuitry

House tokens taken from datarail.org/library (`circuit.css`), adapted for an
outdoor night game:

| Token | Value | Use |
|---|---|---|
| `--bg` | `#05070b` | page void |
| `--bg-soft` | `#0b0f16` | panels / cards |
| `--bg-raised` | `#10151e` | raised surfaces |
| `--fg` | `#f5f5f7` | primary text |
| `--muted` | `#9ca3af` | secondary text |
| `--accent` | `#42f5ff` | rail signal cyan (primary actions, bearings) |
| `--accent-strong` | `#42ffb3` | confirmed / captured green |
| `--warm` | `#e0b25c` | brass rail amber (warnings, frequency marks) |
| `--bad` | `#ff8098` | faults |
| `--line` | `#1c232f` | track-bed borders |

Motifs: hairline "rails" (double rules) under headers, node dots on traces,
mono labels letter-spaced like track signage, circuit-trace corners on cards.
Android mirrors the same hex values in `Theme.kt`.

## Phases (each phase = own commits, tests green before moving on)

1. **Core engine (`core/`)** — rewrite leaf modules first (`Geo`, `Format`,
   `RhythmMatcher`, `PhraseMatcher`, `SignalField`, `Types`), then `Mission`/
   `MissionTools`, `HeadingFusion`, `AudioScene`, `GameEngine`, `WebGame`.
   Gate: `./gradlew -p core jvmTest` green; rebuild web engine
   (`tools/check_web_engine.sh`) whenever `core/` changes.
2. **Server (`server/src/`)** — rewrite module by module (`store`, `players`,
   `accounts`, `server`, `npc`, `stt`, `meta`, `static`, `content`, `billing`,
   `glasses`, `admin`, `audio`). Gate: `cd server && npm test` green.
3. **Web player (`server/play/`, `server/account/`, `server/admin/`)** — rewrite
   JS modules and restyle to the rail-circuitry tokens above. Gate: server tests
   green + manual browser pass (mic, compass, pocket mode).
4. **Android (`app/`)** — restyle `Theme.kt` to house tokens, polish screens
   (`Setup`, `Run`, `Operator`, `Account`), re-implement V1-origin app modules
   with fresh structure. Gate: `:app:assembleDebug` green on a build lane.
5. **Ship** — update `docs/HANDOFF.md`, verify `/health` on the VPS lane,
   push to `main`. No history rewrite at any point.

## Guardrails

- No major change without owner sign-off (this doc is the sign-off record;
  phase boundaries are the check-in points).
- Existing tests are the behaviour contract — rewrite internals, keep them passing.
- No keys/tokens in commits. Public repo rules in `AGENTS.md` still apply.
