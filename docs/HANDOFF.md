# Handoffs

# Handoff — 2026-11-03, Meta Wonder (rewrite deployed)

## What this round does

- Owner play-tested two complete runs successfully, then approved deploy. VPS `/opt/ghostsignal` fast-forwarded `3e77dd3` → `37aab5e` (the full rewrite), server tests run on the VPS, `ghostsignal` service restarted.
- Note: the live lane on this host is `/opt/ghostsignal` + service `ghostsignal` (port 8090 behind Caddy); `deploy/update.sh` in the repo targets a `ghostsignalv2` layout that does not exist on this VPS — deploy manually (fetch, ff-only, `npm test`, restart, health) until that script is corrected.

## Verified

- VPS `npm test` green; service active (new PID); internal and external `/health` 200 with `npc:true, stt:true`; `/play/`, `/account/`, `/admin/` all 200; the rail-circuitry `play.css` is the version served (old neon palette gone).

## Next

- Owner's creative tweaks backlog, whenever ready. Nothing else outstanding for the submission.

# Handoff — 2026-11-03, Meta Wonder (Phase 5: rewrite shipped)

## What this round does

- **Post-hackathon rewrite complete.** All five phases landed on `main` over the disclosed V1 base (`09128e8`): core `881ac2c`, server `91c79d6`, web surfaces `c81326a`, Android `81a0095`. Final verification sweep (below) on the shipped tree. The V1 skeleton stays in history; everything judges run is original post-start code.

## Verified

- Core `./gradlew -p core jvmTest` — 47/47. Server `npm test` — 58/58. Web engine check: bundle matches Kotlin source. Android `:app:assembleDebug` — BUILD SUCCESSFUL.
- Live backend `https://ghostsignal.datarail.org/health` — V2 shape (`stt` present), `npc:true`, `stt:true`, model `muse-spark-1.3`.

## Not verified

- Production still runs the pre-rewrite build; the rewrite is NOT deployed (needs the owner's go + reliable server access). Web player not yet played in a real browser; Android not yet played on a phone. Both are owner passes before the demo.

## Next

- Owner: play once in a browser and once on the phone, then approve the VPS deploy (`deploy/update.sh`) to ship the rewrite live.

# Handoff — 2026-11-03, Meta Wonder (Phase 4: Android rewrite)

## What this round does

- **Post-hackathon rewrite, Phase 4 of 5.** New `Rail` theme in the DataRail house tokens (track-bed/signal/brass, station-sign section headers, node-dot status) replacing the old neon palette across every screen (setup, run, operator, settings, archive, account); radar/fragment strip reworked on the same tokens; `colors.xml`/`themes.xml` aligned. Every app module re-implemented from scratch with the same behaviour: root wiring, settings, data stores, backend client, audio (DSP, PCM, mixer, synth, FMOD bridge), glasses, location, voice, game controller/service.
- Fixed the shipped default backend URL: clean builds now default to `https://ghostsignal.datarail.org` (was the non-resolving `ghostsignalv2.datarail.org`, the field-test STT/NPC failure).

## Verified

- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL (APK 67 MB, 2026-11-03). Core `jvmTest` and server `npm test` still green.

## Not verified

- On-device behaviour (spatial audio, glasses inputs, GPS) — needs a phone pass; FMOD path still requires `third_party/fmod`.

## Next

- Phase 5: full verification sweep and ship (HANDOFF, memory brief, deploy check).

# Handoff — 2026-11-03, Meta Wonder (Phase 3: web surfaces rewrite)

## What this round does

- **Post-hackathon rewrite, Phase 3 of 5.** All web JS re-implemented from scratch: player (`api`, `audio`, `main`, `recorder`, `sensors`, `speech`, `voices`), `account.js`, `admin.js` — modern syntax, same DOM ids, engine JSON contract, and API calls. Player stylesheet restyled to the datarail.org/library house tokens (rail-meets-circuitry: track-bed near-black, signal cyan, brass warnings, rail-rule headers, node dots); admin/account already carried the house tokens and keep them.

## Verified

- `node --check` clean on all 9 rewritten JS files; server `npm test` 58/58.
- Local smoke: `/health`, `/play/`, `/account/`, `/admin/` and assets all 200 with CSP intact.
- DOM contract: all 115 `byId(...)` ids used by the JS exist in the HTML.

## Not verified

- Real-browser play (mic, compass, pocket mode, Media Session) — needs a phone/browser pass by the owner.

## Next

- Phase 4: Android theme/polish to the same tokens.

# Handoff — 2026-11-03, Meta Wonder (Phase 2: server rewrite)

## What this round does

- **Post-hackathon rewrite, Phase 2 of 5.** Every file in `server/src/` re-implemented from scratch (fresh structure/docs, same routes, JSON shapes, error strings and status codes): `accounts`, `admin`, `audio`, `billing`, `content`, `env`, `glasses`, `index`, `meta`, `mission`, `npc`, `players`, `server`, `static`, `store`, `stt`, `tts`.

## Verified

- `cd server && npm test` — 58/58 pass.

## Not verified

- Live VPS behaviour (health/NPC/STT with a real key) — unchanged surface, needs the deployed check at ship time.

## Next

- Phase 3: web player + account/admin rewrite in the rail-circuitry theme.

# Handoff — 2026-11-03, Meta Wonder (Phase 1: core rewrite)

## What this round does

- **Post-hackathon rewrite, Phase 1 of 5** (plan: `docs/REWRITE-PLAN.md`). Every V1-origin file in `core/` was re-implemented from scratch — fresh structure and docs, same public API and behaviour contracts — so the submitted code is original work after the hack start date. The V1 base (`09128e8`) stays in history, disclosed. Rewritten: `Format`, `Geo`, `SignalField`, `Types`, `AudioScene`, `GameEngine`, `HeadingFusion`, `Mission`, `MissionTools`, `RhythmMatcher`, `PhraseMatcher`, `WebGame`. Web engine bundle rebuilt (`server/play/engine/ghostsignal-core.mjs`); the `.d.mts` API surface is unchanged.

## Verified

- `./gradlew -p core jvmTest` — 47/47 pass (fresh results, 2026-11-03).
- `compileKotlinJs` pass; `tools/build_web_engine.sh` rebuilt the bundle cleanly.

## Not verified

- Android app compile (no SDK build this round) and live play; unchanged by this phase.

## Next

- Phase 2: rewrite `server/src/` module by module, `npm test` as the gate.

Newest first. Each agent adds an entry with every change it hands back (see AGENTS.md).

# Handoff — 2026-10-30, Claude (Meta Wonder's briefing for Claude)

## What changed

Meta Wonder's reference packet is now in the repo: CLAUDE.md lists what Claude reads before a
task and the report every task ends with. AGENTS.md adds "Verify a build" (engine, server, app,
FIELD_TEST.md), the `/health` contract (`npc`, `stt`, `model`) and the constraints, one line
each: Meta-only runtime, mission JSON is canon, never hand-edit the web engine, FMOD optional,
APKs staged by commit. The server test now checks `model` too. APK staging is #8.

## Verified

- `npm test` in server/: 58 pass, with the stronger `/health` check.

## Not verified

- APK staging: not built yet (#8).

# Handoff — 2026-10-30, Claude

## What this round does

- **CI is green again.** d9143c0 changed `core/` without rebuilding the web engine, so the
  engine check failed and the APK release was skipped. The engine is rebuilt (f19e19f).
- **V2 runs next to the original** (f1c70d2): app ID `com.ghostsignal.v2` ("GhostSignal V2"),
  `ghostsignalv2.datarail.org`, `/opt/ghostsignalv2`, port 8091, its own Caddy site and secrets.
  The Meta settings (`MODEL_API_KEY`, Muse Spark, Muse Voice) are unchanged.
- **The agent router** (b0c8380): work is GitHub issues, each routed to `agent:claude` or
  `agent:wonder`; PRs from `claude/` or `wonder/` branches go to the other agent for review.
  [AGENTS.md](../AGENTS.md) is the protocol, and [AGENT-ROUTER.md](AGENT-ROUTER.md) covers
  routing and training Muse Glimmer 30B on the routed issues.
- **Open tasks**, from the list below, already routed:
  - Meta Wonder: #1 (live-key shakedown of Muse Spark and Muse Voice), #3 (spoken answers in a
    real browser; needs #1's key).
  - Claude: #2 (Android stops asking for cloud voices), #4 (train Glimmer once there's enough
    routed work).

## Verified

- Server 58/58, router 23/23, the web engine check, shellcheck and the systemd unit.
- CI green on b0c8380 (engine, server, router, Android build), and the APK release published
  as `GhostSignalV2.apk`.
- The router live on GitHub: #1–#4 labelled and commented as `rules.json` says.
- The Android app compiles, including `ServerSpeechInput`: CI's Android job passed on d9143c0.
  So item 2 below is half done; it hasn't run on a phone yet.

## Not verified

- Glimmer: Fireworks isn't reachable from Claude's sandbox, so the Glimmer client is tested
  against a fake endpoint only.
- The PR side of the router (review labels, the handoff check) is tested, but no agent PR has
  gone through it yet.
- V2 isn't deployed: it needs the `ghostsignalv2` DNS record and the installer (DEPLOY.md).

## Next

- Wonder: #1 first.
- Claude: #2.

---

# Handoff — 2026-10-27 (pickup in ~3 hours)

## Where we left off

Voice and character AI now run on **one Meta stack**, and the six review fixes from the code
analysis are in. Everything below is committed together; the next step is a live-key shakedown.

## What this commit does

**Provider switch (ElevenLabs fully removed, Anthropic path removed):**
- Character replies: `server/src/npc.js` now calls the Meta Model API chat endpoint
  (`https://api.meta.ai/v1/chat/completions`, default `NPC_MODEL=muse-spark-1.3`,
  `NPC_EFFORT=low`) via the shared client in `server/src/meta.js`. Persona prompts and
  game-state turns are unchanged, so the engine can't tell the difference.
- Runner speech: new `POST /v1/stt` (`server/src/stt.js`) — clients upload a mono 16 kHz WAV
  clip; the server validates it (415 for anything Muse Voice would refuse) and transcribes
  with `muse-voice-transcribe-1.0`. Expected answers are sent as recognition `keywords`.
  Web player records/downsample/uploads in `server/play/js/speech.js`; Android has a new
  `ServerSpeechInput` (silence-ended push-to-talk) preferred in Auto mode after DAT glasses
  speech, wired in `GameController.chooseSpeechInput`.
- Character voices: on-device only. `POST /v1/tts` remains but answers 501; the web player
  uses browser speech synthesis, the app uses Android TTS (pitch/rate per character).
- `@anthropic-ai/sdk` dependency dropped. Keys are documented in `server/.env.example`:
  one `MODEL_API_KEY` covers both Meta services.

**Review fixes:** rate-limiter maps evict fully-refilled buckets (`server.js`, `players.js`);
sign-up hashes before the exists-check so racing sign-ups can't duplicate an account
(`accounts.js`); TTS disk cache is moot now (module retired); `MissionValidator` rejects
missions with no characters or duplicate character ids (`MissionTools.kt`, tests in
`UnitsTest.kt`); glasses identifier comment corrected to "pepper, not a salt"; `server/.env`
is actually loaded at startup (`server/src/env.js`) and `.env` is gitignored.

## Verified (this session)

- Server: `npm test` — **58/58 pass** (incl. new `server/test/fixes.test.js`).
- Engine: `./gradlew -p core jvmTest` — **44/44 pass** (incl. both new validator tests).
- Startup smoke: `/health` reports `npc/stt` flags and `model: muse-spark-1.3`.
- A real ElevenLabs call earlier proved that path before it was removed; see git history.

## Not yet verified — start here

1. **Add the Meta key.** `server/.env` was deleted (it held only an invalid ElevenLabs key
   ID — the 64-hex ID, not an `sk_` secret; discarded, never committed). Create `server/.env`
   with `MODEL_API_KEY=<Meta Model API key>`, start `node server/src/index.js`, and confirm
   `/health` shows `"npc":true, "stt":true`. Then ask Vesper a question in a real run and
   confirm Spark replies, and say a passphrase to confirm Muse Voice transcription. If the
   model name is rejected, set `NPC_MODEL` in `.env` (any Model API chat model).
2. **Android has never been compiled** (no Android SDK on this machine). First build on an
   SDK machine: `./gradlew :app:assembleDebug`. Watch `ServerSpeechInput.kt` compilation,
   and note `LineVoices` still tries the cloud voice once per line before falling back to
   on-device TTS (fast 501 now, but a wasted round-trip per uncached line).
3. **Web speech is untested in a real browser** — needs mic permission, a run, and a spoken
   challenge answer end-to-end.

## Environment notes

- Temurin JDK 17 is installed at `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
  (set `JAVA_HOME` to it for Gradle). `server/node_modules` is installed but gitignored.
- On this machine, `git` needs `-c safe.directory="C:/Users/Lynn Haven/GhostSignalV2"` under
  the sandbox user, or run from the owner's shell.
