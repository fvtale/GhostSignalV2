# Writing and placing a mission

Everything about the story lives in `app/src/main/assets/mission/ghostsignal.json`. The app and
the backend read the same file, so personas you write here are what the character agent plays.
The core tests validate it (`./gradlew -p core jvmTest`): unknown speakers, missing stems, duplicate
line ids, tiny lock radii and signals placed too close together all fail the build.

## Structure

```jsonc
{
  "id": "ghostsignal-bryant-park",       // change it for a new mission; progress and routes are keyed on it
  "title": "...",
  "world": "...",                        // shared setting, sent to the character agent
  "characters": [ { "id", "name", "role", "persona", "voice": { "ttsVoice", "pitch", "rate" }, "radio" } ],
  "music": { "title", "bpm", "beatsPerBar", "loopBars", "stems": [ { "id", "file", "name", "gainDb" } ] },
  "prologue": [ lines ],                 // optional: spoken before the briefing, under the antagonist's theme
  "briefing": [ lines ],
  "signals": [ ... ],
  "finale": { "lines": [ lines ], "outro": [ lines ] },
  "system": { ... },                     // optional overrides for the SmartSpecs' templated lines
  "soundtracks": [                       // optional: other recordings a runner unlocks (docs/AUDIO.md)
    { "id", "title", "description", "unlockAfterCompletions", "unlockLines": [ lines ], "music": { ... } }
  ],
  "handler": "vesper",                   // who answers free talk while hunting (default: the first briefing speaker)
  "antagonist": { "speaker", "theme", "gainDb", "duck" }   // optional: see below
}
```

A **line** is `{ "id": "unique", "speaker": "characterId", "text": "...", "requires": { "flag": "value" } }`.
`requires` makes a line play only after a story choice, e.g. `{ "fog": "jam" }`.

* `radio` (0–1) is how crushed a character sounds. Vesper is a warm 0.35; Kilo, up in the trees,
  is 0.7; HUSH is unnervingly clean at 0.15.
* `pitch` and `rate` shape each character's on-device voice (Android TTS, browser speech
  synthesis). `voice.ttsVoice` is a legacy cloud-voice id, kept for mission compatibility.
* The handler (Vesper) and the SmartSpecs speak inside your head. Everyone else speaks from the
  direction of their relay.
* The **antagonist** (HUSH) has a looping theme. It holds the frequency during the prologue and
  around any signal whose `speaker` is the antagonist: there it starts at about a third and grows
  as the runner closes in, at full strength during the encounter, and it pushes the pirate music
  down by `duck` (0–1) underneath it. It plays inside the head, because it is everywhere.

## A signal

```jsonc
{
  "id": "undertow",
  "name": "The Undertow",
  "place": "the fountain at the Sixth Avenue end of the park",   // used in hints and by the agent
  "location": { "lat": 40.75398, "lon": -73.98412 },
  "radiusM": 40,        // "near": the approach line plays
  "lockRadiusM": 22,    // capture locks the signal; half the GPS accuracy radius is credited to the runner
  "stem": "bass",
  "speaker": "kilo",
  "teaser": [ ... ],    // when the signal goes live
  "approach": [ ... ],  // first time within radiusM
  "arrival": [ ... ],   // on lock, before the challenge prompt
  "challenge": { "type": "passphrase", "prompt": [ ... ], "phrases": ["fortitude"], "hintAfterAttempts": 2 },
  "success": [ ... ],
  "retry": [ ... ],     // wrong answer
  "hint": [ ... ]       // asked for, or after repeated misses
}
```

Signals are played in order, one at a time, so they only need to be far enough apart for GPS to
tell them apart from the previous one (35 m minimum; 60 m+ is comfortable in Midtown).

## Challenge types

| `type` | Fields | Notes |
| --- | --- | --- |
| `command` | `phrases` | Any phrase, matched forgivingly ("the code" counts as "decode"). |
| `passphrase` | `phrases`, `hintAfterAttempts` | Plays `hint` after that many misses, and lets the runner through after three more. |
| `choice` | `options: [{ id, phrases, reply }]` | Stores the option id as flag `<signalId>`, for `requires` on later lines. |
| `ask` | `maxQuestions`, `acceptPhrases`, `acceptPrompt`, `fallbackReplies` | Free questions go to the character agent; `fallbackReplies` cover the agent being offline. |
| `rhythm` | `onsetsBeats`, `toleranceBeats`, `maxAttempts` | Clicks play on the track's beat grid; tapping is tempo-tolerant. |
| `record` | `maxSeconds` | Saved on the phone only. |
| `tune` | `target`, `targetName`, `toleranceDeg` | Needs head tracking for the full effect; without it, a second capture press passes. Keep the target at least 150 m away. |

## Moving the game to the venue

The authored coordinates are a starting point. On site:

1. Operator → *Venue route*. Walk to each spot, wait for the GPS accuracy to settle, press *Place*.
2. Adjust the lock radius for the conditions (wider if GPS is poor near buildings).
3. *Push shared* (needs the operator token). Other phones *Pull shared*.
4. *Reset* returns to the coordinates in the mission file.

Or do it from a desk on the backend's admin page (`/admin`, see the README): drag the signals on
the map, set their radii and rename them. The server merges changes signal by signal, so a phone
pushing its placements doesn't undo a rename made on the admin page. A phone's push does send
every placement it holds, though, so *Pull shared* before placing more on site.

To move the whole mission somewhere else, edit `location`, `place` and the place names in the
lines; the validator will flag anything inconsistent.

## Working with your story and voice agents

* **Story agents** can generate or rewrite this JSON. Run `./gradlew -p core jvmTest` afterwards; the
  validator checks speakers, stems, ids and geometry.
* **Live character replies** go through `POST /v1/npc` on the backend. Replace
  `server/src/npc.js` with your own agent if you like, as long as it returns `{ "reply": "..." }`
  within about 8 seconds (the app falls back to authored lines after that).
* **Voices** go through `POST /v1/tts`, returning WAV. Swap the provider in `server/src/tts.js`.
