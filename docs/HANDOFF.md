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
