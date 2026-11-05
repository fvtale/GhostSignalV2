# GhostSignal: Audio-Led Spatial Computing, Demonstrated as a Screen-Minimized Alternate Reality Game

**Version 1.1 — November 2026 (technical-accuracy revision)**
Author: Lynette Matusik, designer and sole builder of the GhostSignal codebase (per project owner)
Publisher: DataRail (project programme)
Evidence status: claims are tied to implementation, automated tests, or explicitly labelled owner-reported field experience; a claim-to-evidence map accompanies this paper as `WHITEPAPER-EVIDENCE.md`. This is a product and engineering whitepaper. It is not peer-reviewed research, a validated performance study, or a claim of scientific novelty.

---

## Executive summary

GhostSignal is an audio-led alternate reality game (ARG) played outdoors, on foot, through smart glasses or headphones, with the phone screen relegated to setup and recovery. The player hunts eight audio fragments — the stems of one soundtrack — buried at real locations. Each fragment is rendered from its geographic bearing, heavily filtered and static-laden at distance and clearer as the player approaches. Capturing a fragment joins its stem to the playing mix at the next bar boundary. Completing the route assembles the soundtrack.

Three authored experiences ship in the current build — Bryant Park, Moynihan Train Hall / Penn Station, and lower Central Park, all in New York City — running on one game engine, one cast of voiced characters, and a mission-selection architecture in which an experience is a data file plus audio stems, not new code.

The engineering contribution this paper describes is a heading-estimation approach for spatial audio on hardware that does not provide a fused orientation sensor. Using a gyroscope and an accelerometer expressed in a common device frame, the system projects bias-corrected angular velocity onto an estimated vertical direction derived from gravity, integrates that vertical angular-rate component into a relative heading, and aligns the result to geographic heading using a phone compass calibration and a gated, gain-limited GPS course-over-ground heuristic. "Renting yaw from gravity" is the project's shorthand for this; Section 4 separates the metaphor from the mechanism and states its limits. The approach is best characterised as application-specific systems integration of well-understood inertial techniques (gravity-referenced rate projection, gyro integration, complementary-style correction), not as a new inertial-navigation method.

GhostSignal is positioned by DataRail as a premium experience — authored worlds, integrated sound design, and operator tooling for live events — and as the flagship demonstration of a broader audio-based spatial-computing platform. Sector applications beyond entertainment (education, accessibility-oriented interpretation, and non-weaponized defense and public-safety training) are prospective; no deployed results in those sectors are claimed.

## 1. Experience and design philosophy

### 1.1 The game, plainly

A run takes roughly 15–25 minutes (design target; see `docs/FIELD_TEST.md`) at one site:

1. **Opening.** Over a rain-and-synth ambience, a character (Vesper) greets the player by voice and suggests the nearest experience based on one location fix ("Your nearest location appears to be the train hall on 34th. Is this correct?"). The player confirms or picks another mission. This flow is implemented in both the web player and the Android app.
2. **Hunt.** The next fragment is audible as a filtered, degraded ("clarity"-mapped) version of its stem, co-located in the mix with static and a sonar-style ping from the same bearing. The player navigates primarily by ear and by the SmartSpecs character's spoken updates; the screen is not required during the hunt.
3. **Encounter.** At the signal, a challenge: spoken passphrases and questions (speech recognition with tap/type fallbacks on every challenge), a rhythm-echo task, a facing ("tune") task, a voice-recording task, and command phrases. One signal in each mission is guarded by the antagonist HUSH; the taught counter-command ("jam the signal") is the only accepted response.
4. **Assembly and finale.** Captured stems play in-head; the target stem remains spatialised until captured. A completion unlocks an alternate soundtrack mix for subsequent runs (Bryant Park and Moynihan; Central Park's alternate cut is not yet authored).

"GUI-less" is therefore too strong a term for the whole product, and this paper does not use it unqualified. Installation, permissions, the opening confirmation, mission/soundtrack selection, calibration, and recovery are screen-based. The accurate description is **audio-led gameplay with limited screen-based setup**: during the core loop — hunting, encountering, capturing — the intended interface is spatial audio, voice, and physical buttons (glasses capture button and touchpad, or phone controls).

### 1.2 Design philosophy

The design brief, in the author's own interpretation of the opportunity presented by Meta smart glasses — not an official Meta mission statement, partnership, or endorsement — is to **free people from screens without disconnecting them from their technology or their surroundings**: keep connectivity, assistance, and media, but return visual attention to the place the user is in. GhostSignal operationalises that philosophy by making sound carry navigation, narrative, and reward, and by treating location as named places (a library's steps, a train hall's clock) rather than map pins.

## 2. System architecture and data flow

```text
Glasses (Meta wearables, via DAT session to the phone)
  IMU samples (accel/gyro/orientation, requested 60 Hz), buttons, mic, speakers (Bluetooth audio)
        │  Meta Wearables DAT over the phone's companion link
Android phone app (all game and audio execution)
  ├─ HeadingFusion (core/)  ← glasses IMU, phone compass, GPS course
  ├─ GameEngine (core/)     ← sensors in as events, effects out
  ├─ BinauralMixer          ← real-time binaural render, AudioTrack
  ├─ LineVoices             ← on-device TTS rendering + cache
  └─ BackendClient ──► Server (Node): missions API, live NPC agents (Muse Spark),
                        speech transcription (Muse Voice), accounts, operator tools
Web player (browser): same engine compiled to JS, browser compass + GPS,
  Web Audio HRTF panner, browser speech synthesis (cast voices), server STT
```

Key properties, each verifiable in the repository:

- **One engine.** Game logic, challenge matching, the audio-scene composer, and heading fusion live in a single Kotlin multiplatform core (`core/`), used natively on Android and compiled to JavaScript for the web player. Core game logic is an event-driven state machine: given the same event sequence it transitions deterministically. Live NPC replies are generated by an external model and are explicitly outside that determinism.
- **Missions are data.** One JSON file per experience (world brief, cast and voice specs, dialogue, signal placements, challenges, music manifest, landmark) plus stem audio. `MissionValidator` gates every bundled mission in the test suite, including stem-file existence. The Central Park experience was added with no engine or selector code changes.
- **Execution location.** No GhostSignal code runs on the glasses. The Android phone app runs the engine, fusion, mixing, and speech; the glasses contribute inertial samples, physical inputs, microphone options, and Bluetooth audio output through a Meta Wearables DAT (Device Access Toolkit) session. The web player runs entirely in the browser with the phone's own sensors.
- **Distribution.** Web player served at the project domain; Android as a directly downloadable APK signed with a shared, deliberately non-secret sideload key (updates install over previous builds; this is event/hackathon-grade signing, not store distribution). Server deployment to the production VPS is manual (git checkout + service restart), while pushes to `main` run CI tests and republish a GitHub pre-release APK.

## 3. Gravity-referenced heading estimation

### 3.1 What the sensors actually measure

This section uses standard inertial-sensing facts [1][2]:

- An **accelerometer** measures specific force (in m/s², device frame). At rest or under low-dynamic conditions, the dominant term is the reaction to gravity, so the (sign-convention-dependent) accelerometer direction supports an estimate of the local vertical. During walking, turning, or other acceleration, translational acceleration contaminates that estimate.
- A **gyroscope** measures angular velocity (rad/s, device frame) — a rate, never an absolute heading.
- **Gravity constrains tilt** (pitch/roll relative to the horizon). It provides no information about rotation *about* the vertical (yaw/heading) by itself.
- Absolute geographic heading requires an external reference or an alignment assumption: a magnetometer-based compass (with declination correction for true north), GPS course over ground under suitable motion, or a known starting orientation.

GhostSignal does not create heading information from nothing. It combines the measurements above so that a device with raw inertial output but no fused orientation API can still drive spatial audio.

### 3.2 The implemented estimator

Implementation: `core/src/commonMain/kotlin/com/ghostsignal/core/heading/HeadingFusion.kt` (class `HeadingFusion`), fed by glasses IMU samples converted at the DAT boundary (`GlassesManager.forwardImuSample`, requested rate `MotionSamplingRate.HZ_60`; actual delivered rate is device-dependent and measured in-app), phone compass azimuth (`PhoneCompass`), and GPS fixes (`GpsLocation`).

**Vertical estimate.** The accelerometer vector is smoothed with a per-sample exponential filter (`g ← 0.92·g + 0.08·a`). The effective time constant therefore depends on sample rate (≈0.2 s at 60 Hz). Under the implementation's convention the at-rest accelerometer points away from the ground, and its unit vector **ĝ** is treated as estimated up. Pitch and roll, when no orientation quaternion is present, are derived from this smoothed vector; they inherit the low-dynamic assumption.

**Vertical angular-rate projection** ("renting yaw"). With both vectors in a common device frame, the estimator computes

```text
omega_vertical = dot(omega_measured - bias_estimate, unit_vertical_estimate)
```

and integrates `relative_yaw -= omega_vertical · dt` (converted to degrees). The minus sign is a pure convention: rotation about up is counter-clockwise-positive seen from above, while headings are reported clockwise from north (`Geo.normalizeDeg`, wraparound handled modulo 360°). `dt` comes from sensor timestamps; samples with `dt ≤ 0` or `dt > 0.5 s` do not integrate, bounding the damage from transport stalls.

Why the projection is frame-tolerant — and what it does not forgive: a dot product is invariant when *both* vectors are expressed in the same rotated frame, so the scalar ω_vertical does not depend on knowing which device axis the manufacturer labelled "yaw." It does depend on the gyroscope and accelerometer sharing a calibrated frame (true for a single IMU; an assumption across separate chips), on consistent units and sign conventions, and on usable timestamps. The code carries field-adjustable `invertYaw`/`invertPitch` switches precisely because a mirrored convention cannot be detected from inside the data.

**What ω_vertical is — and is not.** It is the instantaneous angular-velocity component along the estimated vertical. For predominantly upright head motion — the design case — its integral tracks heading change well. It is *not* in general the exact time-derivative of an Euler yaw angle under arbitrary combined pitch, roll, and yaw motion, and the implementation does not claim otherwise: it integrates a vertical-rate approximation. Large sustained tilt combined with rotation will accumulate model error that the correction path (below) can only partially absorb. An alternative `QUATERNION` yaw mode exists that derives yaw from the toolkit's fused orientation quaternion under a stated Z-up-world assumption; the gravity-referenced gyro mode is the default (`YawMode.GYRO`).

**Bias and drift.** Gyro bias is learned only while the head is near-still (|ω| < 0.03 rad/s sustained > 1 s), as a slow exponential average. Between corrections, integration accumulates error from residual bias, noise, timing irregularities, gravity-estimation error, and the vertical-rate approximation. No drift rate is quoted in this paper: it has **not yet been characterised** against a reference instrument (Section 7).

**Staleness.** A glasses estimate is used only if the newest IMU sample is ≤ 1,500 ms old (`FRESH_MS`). Pitch/roll are reported only while the stream is fresh.

### 3.3 North alignment and GPS course: an honest account

The integrated yaw is relative. Alignment to geographic heading is by a single additive offset: `heading = normalize(signed(relative_yaw) + offset)`.

- **Phone-compass calibration** (`calibrateToPhone`): the offset is set from a fresh phone azimuth on the explicit assumption that the runner is facing the phone (the setup UI instructs "hold the phone flat, facing where you look"). The phone azimuth itself comes from Android's `TYPE_ROTATION_VECTOR` — an operating-system fused sensor, not raw magnetometer output — converted with `SensorManager.getOrientation` and corrected by magnetic declination (`GeomagneticField`; default −12.9° for Midtown Manhattan until a GPS fix refines it). The result is *intended* true heading; its accuracy is bounded by the OS fusion, the declination model, and the local magnetic environment (steel structures, vehicles), none of which are characterised here. A phone heading is **not** a glasses heading: it becomes one only through the facing-the-phone alignment assumption, and thereafter only through the integrated gyro.
- **GPS course-over-ground correction** (`onCourse`): GPS course measures direction of *travel*, not gaze. The code uses it as a slow heading heuristic under stated conditions — speed ≥ 0.8 m/s, course fix ≤ 4,000 ms old, glasses stream fresh, and only when the disagreement between current heading and course is < 60° — applying `offset += error × 0.04` per GPS update. At the app's 1 Hz fix rate this is a time constant of roughly 25 updates (≈25 s of walking); the 4% figure is per-update, not a physical time constant, and would change with fix cadence. The 60° gate reduces (it cannot eliminate) corruption from walking while looking sideways; a sustained gaze offset under 60° while walking *will* slowly pull the offset. Standing still, turning in place, and walking backwards produce no valid course correction, by design.
- **Fallbacks** (`estimate`): glasses (aligned, fresh) → phone azimuth (only while the phone is flagged in-hand and fresh) → GPS course (fresh and moving) → no heading. Each substitute is a different physical quantity standing in for head orientation; when heading is absent, the engine deliberately degrades: with no position/heading fix the target stem plays as a faint centred murmur rather than a wrongly-placed one.

Unit tests cover this logic as software behaviour — alignment then a 45°/s turn landing within tolerance of the synthetic truth, the fallback ordering, gravity-tilt pitch, quaternion pitch, and course correction converging within 5° after 30 s of simulated 1.4 m/s walking updates (`HeadingFusionTest`). These are deterministic simulations with idealised inputs. They validate the code's arithmetic and state machine; they do **not** measure real-world angular accuracy, drift, or robustness, which remain uncharacterised (Section 7).

### 3.4 Sensor provenance summary

| Quantity | Source | Rate / notes |
|---|---|---|
| Glasses accel / gyro / orientation quat | Meta glasses via DAT `MotionSample` → `ImuSample` | 60 Hz requested; delivered rate measured in-app, device-dependent; channels may be absent per sample |
| Phone azimuth | Android `TYPE_ROTATION_VECTOR` (OS fusion) + declination | `SENSOR_DELAY_GAME`; remapped when the phone is pitched > 45° |
| Position / course / speed | Android fused location (`GpsLocation`) | 1 Hz requested, high accuracy |
| Web heading | Browser `deviceorientation` / iOS `webkitCompassHeading` (phone compass) | No glasses IMU in the web player; desk mode simulates walking (`SimWalker`) |
| Voice input | DAT glasses mic or phone mic → server transcription (Muse Voice) | Phone recogniser fallback; tap/type always available |

## 4. Audio rendering and soundtrack assembly

**Scene model.** Each engine tick composes an `AudioScene` (`AudioSceneComposer`): collected stems play in-head; the currently hunted stem carries an absolute (compass) world bearing, distance, authored elevation, and a *clarity* value derived from signal strength (`clarity = strength^0.8`, `SignalField`). Static and the sonar ping share the target bearing; the antagonist's theme and a ducking factor are scene-level state.

**Rendering.** The Android `BinauralMixer` renders one stereo `AudioTrack` on a real-time thread in 480-frame blocks at 48 kHz. Bearings reach the mixer in world coordinates; the current listener yaw (and pitch, for relative elevation) is subtracted per block, so orientation changes apply at block granularity. The block period (10 ms) is a code fact, **not** a measured end-to-end latency: sensor transport (DAT/Bluetooth), fusion, render, OS audio, and Bluetooth audio output all add latency that has not been measured end-to-end. Project field documentation (`docs/FIELD_TEST.md`, check 13) anticipates ≈150–250 ms of Bluetooth latency as an expectation to verify — a documented planning assumption, not a measurement result. The mixer's panner is a spherical-head approximation implemented in `Dsp.Binaural` — Woodworth interaural time difference, interaural level difference, a far-ear head-shadow low-pass, rear attenuation/dulling, and spectral elevation approximation. It is not a measured or personalised HRTF. The web player instead uses the Web Audio API's `PannerNode` with its HRTF model (browser-provided, likewise generic). Claims in this paper therefore distinguish:

- *Spatially positioned audio* — sources assigned bearings/elevations relative to the listener: implemented on both platforms.
- *World-locked audio* — rendered direction remaining stable under head motion: this is the design intent and follows from per-block heading subtraction, but perceptual world-locking (stability, latency, externalisation) has **not been formally evaluated**; front/back ambiguity is already noted as an expected limitation in field documentation (check 12).
- *Orientation vs. position*: the system tracks listener orientation (heading/pitch/roll estimates) and GPS position; it does not perform positional (6-DoF) tracking, and sources are placed by bearing and authored elevation rather than by full 3D position.
- *Output hardware*: glasses loudspeakers are open-ear. Binaural cues are still delivered, but without ear-canal isolation; ambient sound, crosstalk, and fit variation reduce localisation precision relative to sealed headphones. No localisation-accuracy measurements on any output device are claimed.

**Assembly.** Stems are equal-length loops, started together and sample-locked (`BinauralMixer.loadStems`, loops sized from the music manifest). A stem whose signal is captured transitions to in-head playback aligned to the next bar boundary (`headLockAtFrame` quantised to frames-per-bar); the soundtrack is thus *assembled through exploration* from authored stems. It is not generatively composed: all musical material is pre-authored (in the current build, synthesised by in-repository tools from written briefs). Alternate soundtrack mixes swap the full stem set under the same stem ids (e.g., Moynihan's After-Hours Cut).

**Voices.** Character lines are rendered on-device: Android renders and caches WAVs via the phone's TTS (`LineVoices`, cache keyed by character voice spec), the web player uses browser speech synthesis with a casting layer (named-voice preference, gender, locale, per-session voice pin) in `voices.js`. Casting quality depends on locally installed voices; Android currently differentiates characters by authored pitch/rate on the engine's default voice (no Android voice selection is implemented). Live free-form character replies are text generated server-side (Muse Spark) and then voiced by the same on-device path. Player speech is transcribed server-side; every spoken challenge also offers tap/type answers, so recognition is never a hard dependency.

## 5. Reference hardware and deployment

Owner-identified reference hardware is **Gen 3 Meta glasses** tethered to an Android phone running the Meta AI companion environment and the Meta Wearables DAT. The repository's integration surface is the DAT (`com.meta.wearable.dat` motion, input, and speech capabilities; session and capability states are surfaced in the setup UI). The repository does **not** record an exact product SKU, firmware version, or DAT version, and publicly available product-generation naming could not be confirmed from repository materials; the paper therefore uses the owner's identification with that qualification. Field documentation (`docs/FIELD_TEST.md`) contains a prepared desk/outdoor test protocol for Gen 3 and Gen 2 glasses with an at-present unrecorded results table, and itself notes the open question of DAT motion support on Gen 3 (desk check 1). Accordingly:

| Tier | Meaning | Examples |
|---|---|---|
| Designed and owner-reported tested | Author reports hands-on use | Gen 3 Meta glasses + Android companion phone |
| Implemented but not field-validated | Code paths exist; no recorded device results in repo | Glasses IMU yaw path at scale (results table empty), DAT speech input, FMOD bridge (no `third_party/fmod` in repo) |
| Architecturally plausible future adaptation | No implementation claim | Other glasses/earbuds/embedded IMUs with comparable API access |

Portability beyond the reference device depends on more than possessing a gyroscope and accelerometer: raw or fused motion API access, sampling timestamps and rate, coordinate conventions, Bluetooth transport latency, audio routing (including the A2DP vs. SCO/HFP trade-off the app already manages), runtime support, and permissions.

Nothing here implies Meta endorsement, certification, or partnership. Deployment status: the production server (single VPS, HTTPS, systemd service), three missions, and the APK download were live and checked by the project at the time of writing; operational status can change and is not guaranteed by this document.

## 6. Evidence, limitations, and what is not yet characterised

**Evidence in hand.** (a) Implementation evidence: the behaviours described in Sections 2–5 exist as inspected code (paths cited; see the companion evidence map). (b) Automated tests: 56 core tests and 75 server tests pass at the time of writing (`gradlew -p core jvmTest`; `npm test` in `server/`), including `HeadingFusionTest` (synthetic-input estimator behaviour), simulated full game runs through all eight signals and challenge types, `MissionValidator` executed over every bundled mission, and the mission registry/selector API. Core tests also run in CI on every push. (c) Live-service checks: health, mission-list, web-player, and APK endpoints responded correctly on the production host when last checked by the project.

**Evidence not in hand.** The following have *not* been characterised and no values for them are asserted anywhere in this paper: heading error versus a reference instrument; yaw drift over time; estimator behaviour under combined sustained tilt and rotation; behaviour while walking and looking away from the travel direction for extended periods; stationary and low-speed behaviour; sensor-dropout recovery rates; end-to-end and component latencies; localisation accuracy or user comfort on glasses speakers versus headphones; GPS accuracy statistics at the three sites; speech-recognition accuracy in situ; battery and thermal behaviour; any usability, learning, accessibility, or operational outcome with human participants. The prepared field protocol in `docs/FIELD_TEST.md` is the intended instrument for several of these; its results table is currently empty.

**Known limitations (design-level).** Urban-canyon GPS error and magnetic disturbance bound placement and compass alignment; the gyro path drifts between corrections and relies on walking for course correction; open-ear playback and generic binaural rendering bound localisation; on-device voices bound character performance; datalink (DAT/Bluetooth plus, for transcription and NPC replies, network access to the server) bounds responsiveness, with offline fallbacks (cached voices, tap/type answers, non-directional cues) rather than offline parity.

**Privacy and safety (actual data flows).** Location, compass, IMU, and microphone are permission-gated by the platforms. Audio clips for transcription are recorded only during listening windows and are sent to the project server, which forwards them to the configured transcription API without writing clips to disk in server code (third-party API handling is outside this repository and not audited here). Live NPC requests send mission context (coarse progress information) and conversation turns to the configured model API. Character voices are synthesised on-device; no voice audio leaves the device except player-recorded challenge messages stored on the phone. GhostSignal is an entertainment product, **not a navigation aid**: players are instructed to watch the street (event runbook, `docs/FIELD_TEST.md`), audio is delivered open-ear partly so environmental sound remains audible, and nothing in the system should be relied upon for wayfinding or hazard awareness.

## 7. Validation roadmap (proposed, not performed)

1. **Heading accuracy**: glasses-heading versus a reference (e.g., optical or survey-grade heading) over scripted yaw/pitch/roll sequences, including combined tilt-and-turn cases that stress the vertical-rate approximation.
2. **Drift and correction**: yaw error growth at rest and in motion over 5–30 minutes, with and without course correction; walking-while-looking-sideways cases at several gaze offsets, including > 60°.
3. **Dropouts and stale data**: DAT interruptions of varying length; recovery behaviour and time-to-realign; low-speed and stationary GPS behaviour.
4. **Latency budget**: timestamped breakdown (IMU arrival, fusion, render block, OS/Bluetooth output) and total motion-to-sound latency on reference hardware.
5. **Perception**: localisation error (front/back confusion rate) and externalisation ratings on glasses speakers vs. sealed headphones; comfort over a full run.
6. **Field robustness**: GPS error distributions at the three authored sites; speech-recognition success in situ; battery/thermal per run; completion and stuck-point rates with first-time players.
7. **Human-factors and accessibility studies**, designed with the relevant user communities, before any accessibility, educational-outcome, or training-efficacy claims are made.

## 8. Platform positioning and prospective applications

**Positioning.** "Premium" in this paper names an intended product position — authored, place-specific experiences; integrated sound, story, and interaction design; consistent character voices; and operator capabilities that exist in the build (venue route placement, deployed soundtrack management, live transmissions to runners, operator force-lock/force-solve) — not established pricing, revenue, partnerships, or adoption, none of which are claimed.

**What is demonstrated now.** A complete, deployed, screen-minimized ARG platform: one engine, three authored experiences, spatialised stem-hunting, a gravity-referenced heading estimator with compass/GPS alignment, on-device character voices, live AI NPC dialogue within authored rails, and event-operator tooling.

**What is reusable.** The engine and challenge system; the heading estimator and its calibration/correction policy; the audio-scene/binaural rendering model; the mission authoring format and validator; the casting approach to on-device voices. New experiences are content projects, not engine projects.

**Prospective applications — explicitly not yet demonstrated.** In education: museum, campus, and historical interpretation where the artefact remains the visual subject; language-learning and site-based inquiry using the implemented ask/answer interactions; these are plausible uses of implemented mechanics, with no learning outcomes yet evaluated. In accessibility: audio-first interpretation has clear potential value for some low-vision visitors, but suitability — let alone independent navigation — requires dedicated research with blind and low-vision users and is not claimed. In defense and public safety: the prospective interest is non-weaponized — eyes-free instruction, simulation and rehearsal, and location-triggered training scenarios using the heading estimator and voice interaction model. No combat, targeting, operational-navigation, or safety-certification capability is claimed or implied; any safety-relevant deployment would require separate validation, human-factors evaluation, security and privacy review, and requirements-specific engineering well beyond this codebase's current evidence.

## 9. Conclusion

GhostSignal demonstrates, in deployed code, that a complete screen-minimized spatial-audio experience can be built on authored data and modest sensors: eight stems to find, a track assembled by walking, characters who answer out loud, and a heading solution that rents its yaw from gravity and borrows its north from a compass glance and the walk itself. The metaphor survives the audit because the mechanism is ordinary, inspectable, and honestly bounded: gravity gives tilt, the gyroscope gives rate, integration gives relative heading, and external references keep it geographic. What remains is the unglamorous, decisive work of Section 7 — measuring how well it all holds up on heads, in streets, over time. That measurement programme, more than any single trick, is what would turn a compelling demonstration into a platform others can build on.

---

## References

[1] Android Developers, "Position sensors" (rotation vector, `SensorManager.getOrientation`, sensor coordinate systems). https://developer.android.com/develop/sensors-and-location/sensors/sensors_position

[2] Android Developers, `GeomagneticField` (magnetic declination from position and time). https://developer.android.com/reference/android/hardware/GeomagneticField

[3] W3C, "DeviceOrientation Event Specification" (browser compass/orientation events used by the web player). https://www.w3.org/TR/orientation-event/

[4] R. S. Woodworth & H. Schlosberg, *Experimental Psychology* (1954) — spherical-head interaural time-difference model underlying `Dsp.Binaural`. (Historical source; implemented approximation described in Section 4.)

## Appendix A — Selected implementation map

| Concern | Implementation |
|---|---|
| Heading estimation | `core/src/commonMain/kotlin/com/ghostsignal/core/heading/HeadingFusion.kt` |
| Heading tests (synthetic) | `HeadingFusionTest`, `core/src/jvmTest/kotlin/com/ghostsignal/core/UnitsTest.kt` |
| Mission model & validation | `core/src/commonMain/kotlin/com/ghostsignal/core/model/Mission.kt`, `MissionTools.kt` |
| Audio scene composition | `core/src/commonMain/kotlin/com/ghostsignal/core/engine/AudioScene.kt` |
| Android binaural mixer / DSP | `app/src/main/kotlin/com/ghostsignal/app/audio/BinauralMixer.kt`, `Dsp.kt` |
| Glasses DAT boundary | `app/src/main/kotlin/com/ghostsignal/app/glasses/GlassesManager.kt` |
| Phone compass / GPS | `app/src/main/kotlin/com/ghostsignal/app/location/Location.kt`, `GpsLocation.kt` |
| On-device voices (Android / web) | `app/src/main/kotlin/com/ghostsignal/app/voice/LineVoices.kt`; `server/play/js/voices.js` |
| Server, missions API, NPC/STT | `server/src/{server,mission,npc,stt}.js` |
| Field-test protocol (results pending) | `docs/FIELD_TEST.md` |

## Author

**Lynette Matusik** is the designer and sole builder of the GhostSignal codebase, as specified by the project owner (authorship is owner-attested; it has not been independently audited for this paper, and no other qualifications or affiliations are claimed). GhostSignal is presented as a premium DataRail experience and the flagship demonstration of the broader audio-based spatial-computing platform described in Section 8.

*GhostSignal — a premium DataRail experience. GHOST 93.3: stay until the light changes.*
