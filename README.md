# GhostSignal V2

An audio-led alternate reality game for Meta smart glasses. You walk a real place, hunting eight
hidden audio signals by ear. Each one you capture, by winning a challenge from a character, adds
a layer to the soundtrack. The screen is for setup; the game is sound.

**Play it:** [ghostsignal.datarail.org](https://ghostsignal.datarail.org) has the web player
(any phone browser, with headphones or glasses) and the Android app.

Three experiences ship today, all in New York City: Bryant Park, Moynihan Train Hall and Central
Park. An experience is one data file plus audio stems, not new code.

## How it works

```text
Glasses (Meta Wearables Device Access Toolkit): head motion, touchpad, mic, speakers
      │
Android app ── or ── Web player (browser)
      │  both run the same game engine
Server (Node): missions, characters (Muse Spark), speech (Muse Voice), accounts, admin
```

- **One engine, two front-ends.** The game rules are written once in Kotlin (`core/`) and run
  natively on Android and, compiled to JavaScript, in the browser.
- **Spatial audio.** Each signal plays from its real-world bearing and elevation, tracked to your
  head, and gets clearer as you approach.
- **Built on Meta's stack.** Characters answer through Muse Spark, speech is transcribed by Muse
  Voice, and the glasses connect through Meta's Wearables Device Access Toolkit.
- **Missions are data.** Story, cast, route and music live in
  `app/src/main/assets/mission/*.json`.

The [whitepaper](docs/WHITEPAPER.md) covers the design and the heading-estimation approach in
depth, with its [evidence map](docs/WHITEPAPER-EVIDENCE.md).

## What's in the repository

| Path | What it is |
| --- | --- |
| `core/` | The game engine: state machine, signal field, heading fusion, speech and rhythm matching, audio scene. Kotlin Multiplatform, with unit tests. |
| `app/` | The Android app: glasses link, 3D audio mixer, speech, voices, GPS, operator tools. |
| `server/` | The Node server, with no dependencies: accounts, premium, glasses linking, admin API, characters and speech. Also the web pages: `play/`, `admin/`, `account/`, `landing/`. |
| `tools/` | Generators for every soundtrack, and the web engine build. |
| `deploy/` | Server install and update scripts ([DEPLOY.md](DEPLOY.md)). |
| `docs/` | Whitepaper, audio guide, mission authoring guide, field-test checklist, handoff log. |
| `.github/agent-router/` | The router that splits work between two coding agents ([docs/AGENT-ROUTER.md](docs/AGENT-ROUTER.md)). |

## Run it yourself

Needs Node 20 or newer; JDK 17 for the engine and the app.

```bash
cd server && npm test                 # server tests
node src/index.js                     # then open http://localhost:8080/play/
./gradlew -p core jvmTest             # engine tests
./gradlew :app:assembleDebug          # Android APK (needs the Android SDK)
```

Without a `MODEL_API_KEY` in `server/.env`, characters use their written lines and spoken
answers fall back to tapping or typing; everything else works. `server/.env.example` lists the
settings.

## How it was built

V2 began as a copy of the earlier private GhostSignal prototype (commit `09128e8`) and was then
re-implemented from scratch on top of it during the hackathon;
[docs/REWRITE-PLAN.md](docs/REWRITE-PLAN.md) explains how to see that in the history. Two coding
agents, Claude and Meta Wonder, work on it under [AGENTS.md](AGENTS.md).

MIT licensed. GhostSignal is a DataRail project: [datarail.org](https://datarail.org).
