# GhostSignal whitepaper — claim-to-evidence map

Companion to `docs/WHITEPAPER.md` (v1.1). Categories: **Implemented** (in code) · **Tested** (automated tests) · **Owner-reported** (project owner's account, not independently verified) · **Field-documented** (documented field measurements/protocols) · **Proposed** (planned/future).

| # | Claim (whitepaper §) | Implementation / test reference | Category | Qualification |
|---|---|---|---|---|
| 1 | Audio-led ARG: 8 stems hunted spatially and assembled into a soundtrack (§1) | `core/.../engine/AudioScene.kt` (`AudioSceneComposer`), `GameEngine.kt`; `GameEngineTest.playFullRun` | Implemented, Tested | Simulated runs; real-world completion rates unmeasured |
| 2 | Screen-minimized: screen used for setup/recovery (§1) | `server/play/index.html` (opening/setup/run), `app/.../ui/SetupScreen.kt`, `Opening.kt` | Implemented | "GUI-less" not claimed for the whole product |
| 3 | Opening: ambience, Vesper welcome, nearest-landmark confirm (§1) | `server/play/js/main.js` opening flow, `app/.../ui/Opening.kt`; landmarks in mission JSON + `core/.../model/Mission.kt` (`Landmark`) | Implemented | Not yet exercised end-to-end by an independent reviewer on device |
| 4 | Three experiences ship; selector needs no code change per mission (§1, §2) | `app/src/main/assets/mission/{ghostsignal,moynihan,centralpark}.json`; `server/src/mission.js` registry; Android `MissionRepository` | Implemented, Tested | `missions.test.js`; core `everyBundledMissionIsValid` |
| 5 | Gravity-referenced vertical angular-rate projection (§3) | `HeadingFusion.integrateGyro` (`HeadingFusion.kt:129-149`) | Implemented | Vertical-rate approximation, not exact Euler yaw rate under arbitrary tilt |
| 6 | Frame-tolerance via dot product (§3) | `HeadingFusion.kt:147` | Implemented | Requires common calibrated gyro/accel frame; invert switches exist for sign conventions |
| 7 | Bias learning while still (§3) | `HeadingFusion.trackGyroBias` | Implemented, Tested (indirectly) | Stillness-gated; real drift uncharacterised |
| 8 | Phone-compass calibration; declination-corrected azimuth (§3) | `Location.kt` `PhoneCompass` (`TYPE_ROTATION_VECTOR`, `GeomagneticField`, default −12.9°) | Implemented | Accuracy depends on OS fusion + magnetic environment; not measured |
| 9 | GPS course correction: speed/freshness/disagreement gates, 0.04/update (§3) | `HeadingFusion.onCourse` (0.8 m/s, 4 s, 60°, gain 0.04) | Implemented, Tested (`walkingCourseCorrectsGyroDrift`) | Heuristic; gaze/travel misalignment can pull offset; ≈25-update time constant at 1 Hz |
| 10 | Fallback chain glasses→phone→course→none; centred fallback audio (§3) | `HeadingFusion.estimate`; `AudioSceneComposer` no-reading branch | Implemented, Tested (`fallsBackToPhoneThenCourseThenNothing`) | Substitutes are different physical quantities than head heading |
| 11 | Per-block listener orientation in the mixer (§4) | `BinauralMixer.setListenerOrientation`, 480-frame blocks @48 kHz | Implemented | 10 ms block period only; end-to-end latency not measured |
| 12 | Spherical-head binaural approx. (Android); HRTF panner (web) (§4) | `Dsp.kt` `Binaural`; `server/play/js/audio.js` `makePanner` | Implemented | Not measured/personalised HRTF; open-ear limits unmeasured |
| 13 | Bar-aligned stem capture; sample-locked loops (§4) | `BinauralMixer` (`headLockAtFrame`, `loopFrames`) | Implemented, Tested (engine scene tests) | — |
| 14 | On-device character voices; web casting; Android pitch/rate only (§4) | `LineVoices.kt`/`AndroidTts`; `server/play/js/voices.js` `CASTING` | Implemented, Tested (`voices.test.js`) | Quality depends on installed voices; Android has no voice selection |
| 15 | Player STT via server; tap/type always available (§4) | `server/src/stt.js` (no clip persistence in server code), `server/play/js/speech.js`, challenge UI | Implemented, Tested (server) | Third-party API handling outside repo; accuracy in situ unmeasured |
| 16 | Live NPC replies (Muse Spark) within authored missions (§2, §4) | `server/src/npc.js`, `meta.js`; `POST /v1/npc` | Implemented, Tested (server mocks) | Live quality/latency depends on external API |
| 17 | No code runs on glasses; phone executes all (§2, §5) | `GlassesManager.kt` DAT session; `GameController` on phone; Bluetooth audio (`AudioRouting.kt`) | Implemented | — |
| 18 | Reference hardware: Gen 3 Meta glasses (§5) | DAT integration `com.meta.wearable.dat`; `docs/FIELD_TEST.md` protocol mentions Gen 3/Gen 2 | Owner-reported | Exact SKU/firmware/DAT version not recorded in repo; Gen 3 DAT support questioned in FIELD_TEST check 1 |
| 19 | Field performance (heading accuracy, drift, latency, battery) (§6, §7) | `docs/FIELD_TEST.md` protocol; results table empty | Proposed | No field measurements recorded; Bluetooth 150–250 ms figure there is an expectation, not a result |
| 20 | Live production service + APK download (§5) | VPS deployment (manual git + systemd), `GET /health`, `/v1/missions`, `/download/ghostsignal.apk` | Owner-reported (checked at time of writing) | Operational status can change; not independently monitored |
| 21 | Premium positioning; sector applications (§8) | Operator tooling exists (`server/src/admin.js`, operator screens) | Proposed (positioning) | No pricing, adoption, learning, accessibility, or training outcomes claimed or evidenced |
| 22 | Sole authorship by Lynette Matusik | Project owner specification | Owner-reported | Not independently audited; no further qualifications claimed |
