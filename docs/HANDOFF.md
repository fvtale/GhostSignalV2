# Handoffs

Newest first. Each agent adds an entry with every change it hands back (see AGENTS.md).

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
