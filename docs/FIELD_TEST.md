# Field test and event-day checklist

GhostSignal is built to degrade gracefully, but several things can only be learned with real
glasses outdoors. Run this checklist with **both** the Gen 3 and the Gen 2 glasses, on the Moto
G Power phones you'll use at the event. Note results in the table at the end: they decide which
features you advertise and which fallbacks you keep on.

## 1. Desk checks (30 minutes)

| # | Check | How | Pass | If it fails |
| --- | --- | --- | --- | --- |
| 1 | DAT session starts | Setup → Connect via Meta AI, then Start session | Session `started`, device name and battery shown | Meta AI Developer Mode on? Firmware and Meta AI app up to date? Gen 3 may not be supported by DAT yet: try the Gen 2 |
| 2 | Buttons arrive | Press capture, hold capture, double-press, tap, swipe each way | *Last press* shows each one | Inputs not enabled for the app in Wearables Developer Center → touchpad media buttons still work as SCAN/HINT/REPEAT |
| 3 | Swipe directions | Swipe forward and back | Note which of `left/right/up/down` each produces | Swap the mapping in `GlassesManager.onInput` if forward/back feel wrong |
| 4 | Head tracking rate | Setup → Head tracking | 30–60 Hz | Motion not enabled → game uses phone compass / walking direction |
| 5 | Head tracking direction | Calibrate, then *Test spatial audio*, turn your head right | The ping moves toward the front and stays fixed in the room | Moves the wrong way → Settings → *Invert yaw*. Wanders → try *Orientation quaternion* |
| 6 | Glasses speech | Allow glasses mic, start a run, Force lock at signal 1, say "decode" | Transcript appears, signal unlocks | Speech not enabled → Settings → Voice input → *Phone recognizer, glasses mic* |
| 7 | Audio quality while the mic is open | During a listening window, listen to the music | Note whether it collapses to mono/call quality | Expected with the phone recognizer (Bluetooth call profile); the walkie-talkie effect is designed to hide it. With DAT Speech it may stay clean |
| 8 | Voices | Prepare transmissions with the backend set | `cloud` count = all lines | Check backend health, ElevenLabs key and voice ids |
| 9 | Character agent | Hold capture and ask Vesper a question | In-character reply within ~3 s | Backend logs; try `NPC_MODEL=claude-haiku-5-5` if slow |

## 2. Outdoor checks in Bryant Park (1 hour)

Around Halloween, Bryant Park's Winter Village (ice rink and holiday shops) usually occupies the
lawn, and its setup closes parts of the park for weeks beforehand. The authored route keeps
signals on the terraces and perimeter walkways, but walk it first and re-place any signal that
ends up behind a fence (Operator → *Venue route* → *Place*).

| # | Check | Pass | If it fails |
| --- | --- | --- | --- |
| 10 | GPS accuracy at each signal | Operator shows ±15 m or better | Widen the lock radius (Operator → slider); place signals in open sky, away from the Library facade |
| 11 | Route placement | Walk the route, press *Place* on each spot, *Push shared* | All 8 placed; second phone *Pull shared* gets them | Check `OPERATOR_TOKEN` |
| 12 | Beacon direction | With the phone in your pocket, does the signal sound like it's in the right direction? | Mostly right, front/back sometimes ambiguous | Recalibrate; if heading drifts quickly, rely on distance cues and the SmartSpecs' clock-face directions |
| 13 | Head-tracking latency | Turn your head quickly toward a beacon | The source stays put, maybe with a short lag | Bluetooth adds ~150–250 ms; if it's disorienting, keep head tracking only for the Tune challenge and lower beacon clarity changes |
| 14 | Open-ear audibility | Normal park noise, volume 80–90% | Voices intelligible, music present | Raise master volume; lower `radio` on characters in the mission file |
| 15 | Mic in noise | Answer each challenge near the fountain (loud) | Recognized within two tries | Add more phrase variants to the challenge; passphrases hint automatically after two misses |
| 16 | Battery and heat | Full run, screen off | Phone > 60%, glasses > 50%, no thermal warnings | Shorter route, fewer prepared voices, lower IMU rate (`HZ_30` in `GlassesManager`) |
| 17 | Full run | All 8 signals, finale | 15–25 minutes, no operator help | Note where people got stuck and soften that challenge |

## 3. Event-day runbook

**Before doors**
- Charge everything. Each phone: GhostSignal open, setup screen green, *Prepare transmissions* done (story lines then never wait on the network).
- Operator → *Pull shared* on every phone so all runners use the same placements, names and
  deployed soundtracks. The status line should say every deployed soundtrack is ready.
- Leave shared event phones signed out of player accounts (setup → *Account*). A signed-in phone
  adds every runner's finished runs to that one account, and *Re-lock rewards* can't take them back.
- Do one quick run of signal 1 per phone to wake GPS.
- Backend: `/health` shows `npc: true, tts: true`.

**Per runner (about 2 minutes)**
1. Glasses on, phone in pocket after setup. Explain three things: *capture* to lock and answer, *hold capture* to talk, *say pause* to stop. Remind them to watch the street.
2. *Start new run*. The briefing explains the rest.

**Running the room**
- The operator screen sends live transmissions to every runner (*Live transmission*). Use them for hints, story beats ("HUSH patrols at the fountain") or to call runners back to the booth for the DJ set.
- Stuck runner: Operator → *Force lock* / *Force solve* on their phone, or *Jump to* a signal.
- Messages runners recorded are on each phone under *Messages*. Ask before playing any of them publicly.

## Results

| Check | Gen 3 | Gen 2 | Notes |
| --- | --- | --- | --- |
| 1 Session | | | |
| 2 Buttons | | | |
| 3 Swipe mapping | | | |
| 4 IMU rate | | | |
| 5 Head direction | | | |
| 6 Glasses speech | | | |
| 7 Audio during mic | | | |
| 10 GPS accuracy | | | |
| 13 Head-tracking latency | | | |
| 15 Mic in noise | | | |
| 16 Battery after a run | | | |

## Playing in the browser

If runners use the web player (`/play` on the backend) instead of the app:

- Use Chrome on Android. Pair the glasses with the phone first; the page's **Sound check** should
  send a ping round your head, through the glasses and not the phone speaker.
- Try the touchpad: a tap should press *capture* (the page shows the press). If it doesn't, the
  phone gave media keys to another app: close music apps and play the sound check again.
- Answer one challenge out loud and one by tapping. Note which microphone the recognizer used;
  if it's the phone's, runners hold the phone up to answer, or tap.
- Put the phone in your pocket in **Pocket mode** and walk to a signal. The screen must stay on:
  check that battery saver doesn't lock it, and that pocket touches don't press capture too often.
- Sign in on one phone and check the cloud voices and a live answer from the Archivist.

## Linking glasses

- Sign in on the app, connect a pair, tap *Account* → *Link these glasses*. On the admin page's
  **Glasses** tab the request should show the right model, the name the owner gave the pair and its
  firmware. Approve it and check the app shows *Premium* after *Account* refreshes.
- Find out whether the toolkit's identifier is stable: unpair the glasses in the Meta AI app, pair
  them again and link once more. If the fingerprint on the Glasses tab changes, the same pair will
  show up as a new request after re-pairing (approve it again, and revoke the old one).
- Try the same pair from a second account: the server should refuse it while it's linked to the first.
- Event glasses: on the operator phone, connect each pair of the box in turn and tap Operator →
  *Event glasses* → *Enroll*. A runner linking one of them gets premium without waiting.
