// GhostSignal in the browser. This drives the same engine the Android app
// runs (core/, compiled to JS) the way the app's GameController does:
// sensors and buttons in as events, effects out to audio, voices, the
// microphone, and the screen. The phone runs everything; the glasses are
// its Bluetooth speakers, microphone, and buttons.

import * as Engine from "../engine/ghostsignal-core.mjs";
import { api, store } from "./api.js";
import { AudioEngine } from "./audio.js";
import { Recorder } from "./recorder.js";
import { Compass, Gps, SimWalker, step } from "./sensors.js";
import { Speech } from "./speech.js";
import { Voices } from "./voices.js";

const byId = (id) => document.getElementById(id);
const HOLD_TO_TALK_MS = 450;
const NPC_TIMEOUT_MS = 10_000;

const loaded = {
  missionJson: null,
  mission: null,
  routeJson: null,
  catalog: null,
  catalogJson: null,
  plan: null,
};

let audioEngine = null;
let activeRun = null;

/** Small DOM builder: attrs may set text/class, on* handlers, plain attrs. */
function createElement(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "text") node.textContent = value;
    else if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children) {
    if (child !== null && child !== undefined) node.append(child);
  }
  return node;
}

const measureBetween = (lat1, lon1, lat2, lon2) =>
  Engine.distanceAndBearing(lat1, lon1, lat2, lon2);

function ensureAudio() {
  if (!audioEngine) {
    audioEngine = new AudioEngine();
    audioEngine.setVolume(store.settings().volume);
  }
  return audioEngine;
}

// -- setup ---------------------------------------------------------------------

async function boot() {
  try {
    const mission = await api.mission();
    loaded.mission = mission;
    loaded.missionJson = JSON.stringify(mission);

    const [route, catalog, plan] = await Promise.all([
      api.route(mission.id).catch(() => null),
      api.tracks(mission.id).catch(() => null),
      api.me(),
    ]);
    loaded.routeJson = route ? JSON.stringify(route) : null;
    loaded.catalog = catalog;
    loaded.catalogJson = catalog ? JSON.stringify(catalog) : null;
    loaded.plan = plan;

    syncCompletionCount(mission.id, plan);
    byId("loading").hidden = true;
    renderSetup();
    byId("setup").hidden = false;
  } catch (error) {
    byId("loading").hidden = true;
    byId("fatal").hidden = false;
    byId("fatal").textContent = `Couldn't reach the GhostSignal server: ${error.message}`;
  }
}

/** Finished runs follow a signed-in runner; keep the higher count in sync. */
function syncCompletionCount(missionId, plan) {
  const rewards = store.rewards();
  const remoteCount = plan?.account?.completions?.[missionId] ?? 0;
  if (remoteCount > rewards.completions) {
    store.saveRewards({ ...rewards, completions: remoteCount });
  } else if (plan && rewards.completions > remoteCount) {
    api.progress(missionId, rewards.completions).catch(() => {});
  }
}

function renderSetup() {
  const plan = loaded.plan;
  const whoLink = byId("who");
  whoLink.textContent = plan ? plan.account.email : "Sign in";
  whoLink.href = plan ? "/account/" : "/account/?return=/play/";

  renderSoundtrackChoices();

  const settings = store.settings();
  document.querySelectorAll('input[name="mode"]').forEach((input) => {
    input.checked = input.value === settings.mode;
    input.onchange = () => store.saveSettings({ ...store.settings(), mode: input.value });
  });

  byId("accountText").textContent = plan
    ? `Signed in as ${plan.account.email}${plan.account.premium.active ? " · premium" : ""}. This browser reads the lines aloud, characters answer your questions live, and your finished runs follow you.`
    : "Signed out: this browser reads the lines in its own voice, and characters answer with written lines. Sign in for live answers and premium soundtracks.";

  const saved = store.progress();
  const resumable = saved && !saved.complete &&
    saved.missionId === loaded.mission.id && saved.collected.length > 0;
  byId("resume").hidden = !resumable;

  byId("micNote").textContent = new Speech().available
    ? "To answer out loud. You can always tap the answer instead."
    : "No microphone here, so you'll tap or type your answers.";
}

function renderSoundtrackChoices() {
  const rewards = store.rewards();
  const list = byId("soundtracks");
  list.textContent = "";

  const choices = JSON.parse(
    Engine.soundtrackChoices(loaded.missionJson, loaded.catalogJson, rewards.completions),
  );
  const pickedId = choices.some((c) => c.id === rewards.soundtrack && c.unlocked)
    ? rewards.soundtrack
    : "original";

  for (const choice of choices) {
    const runsLabel = choice.unlockAfterCompletions === 1
      ? "a run"
      : `${choice.unlockAfterCompletions} runs`;
    const input = createElement("input", {
      type: "radio",
      name: "soundtrack",
      value: choice.id,
      disabled: !choice.unlocked,
      checked: choice.id === pickedId,
    });
    input.addEventListener("change", () =>
      store.saveRewards({ ...store.rewards(), soundtrack: choice.id }));
    list.append(createElement(
      "label",
      { class: choice.unlocked ? "choice" : "choice locked" },
      input,
      createElement("span", {},
        createElement("b", { text: choice.title }),
        choice.unlocked ? choice.description : `Finish ${runsLabel} to unlock`),
    ));
  }

  for (const lockedTrack of loaded.catalog?.locked ?? []) {
    list.append(createElement(
      "label",
      { class: "choice locked" },
      createElement("input", { type: "radio", disabled: true }),
      createElement("span", {},
        createElement("b", {}, lockedTrack.title,
          createElement("span", { class: "tag", text: "PREMIUM" })),
        lockedTrack.description || "For premium players"),
    ));
  }

  const unlockedCount = choices.filter((c) => c.unlocked).length;
  byId("soundtrackNote").textContent = loaded.catalog?.locked?.length
    ? (loaded.plan
      ? "Premium soundtracks come with premium, on the account page."
      : "Sign in for premium soundtracks.")
    : (unlockedCount < choices.length ? "Finish runs to unlock more." : "");
}

function markCheck(checkId, ok, note) {
  const item = byId(checkId);
  item.classList.toggle("done", ok === true);
  item.classList.toggle("warn", ok === false);
  if (note) item.querySelector("span").textContent = note;
}

function capitalise(text) {
  return `${text[0].toUpperCase()}${text.slice(1)}`;
}

byId("soundCheck").addEventListener("click", async () => {
  const engine = ensureAudio();
  await engine.resume();
  engine.soundCheck();
  markCheck("checkAudio", true,
    "A ping went round your head: left, behind, right, ahead. If it came from the phone, connect the glasses and try again.");
});

byId("allowLocation").addEventListener("click", async () => {
  try {
    const accuracy = await Gps.check();
    markCheck("checkLocation", true, `Location on, accurate to about ${Math.round(accuracy)} m.`);
  } catch (error) {
    markCheck("checkLocation", false, `${capitalise(error.message)}. You can still try it at a desk.`);
  }
});

byId("allowCompass").addEventListener("click", async () => {
  try {
    await Compass.permit();
    let received = false;
    const compass = new Compass((degrees) => {
      received = true;
      markCheck("checkCompass", true, `Compass on: facing ${Math.round(degrees)}°.`);
    });
    compass.start();
    setTimeout(() => {
      compass.stop();
      if (!received) {
        markCheck("checkCompass", false,
          "No compass reading. The game will use your walking direction instead.");
      }
    }, 2500);
  } catch (error) {
    markCheck("checkCompass", false,
      `${capitalise(error.message)}. The game will use your walking direction instead.`);
  }
});

byId("allowMic").addEventListener("click", async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    stream.getTracks().forEach((track) => track.stop());
    markCheck("checkMic", true, new Speech().available
      ? "Microphone on. Answer out loud, or tap."
      : "Microphone on (for the Record challenge). Tap or type your answers.");
  } catch {
    markCheck("checkMic", false, "Microphone blocked. Tap or type your answers instead.");
  }
});

byId("start").addEventListener("click", () => startRun(false));
byId("resume").addEventListener("click", () => startRun(true));

// -- a run -----------------------------------------------------------------------

async function startRun(resume) {
  const engine = ensureAudio();
  await engine.resume(); // Inside the tap, so the browser allows sound.
  byId("setup").hidden = true;
  byId("run").hidden = false;
  byId("objective").textContent = "Loading the soundtrack…";

  const rewards = store.rewards();
  const game = new Engine.WebGame(
    loaded.missionJson, loaded.routeJson, loaded.catalogJson,
    rewards.soundtrack, rewards.completions,
  );
  const mission = JSON.parse(game.missionJson());

  const buffers = new Map();
  await Promise.all(mission.music.stems.map(async (stem) => {
    try {
      buffers.set(stem.id, await engine.decode(await api.audio(stem.file)));
    } catch (error) {
      console.warn(`stem ${stem.file} didn't load`, error);
    }
  }));

  let themeBuffer = null;
  if (mission.antagonist?.theme) {
    try {
      themeBuffer = await engine.decode(await api.audio(mission.antagonist.theme));
    } catch (error) {
      console.warn("theme didn't load", error);
    }
  }

  engine.startMusic(mission.music, buffers);
  engine.setTheme(themeBuffer, mission.antagonist?.gainDb ?? 0);

  activeRun = new Run(game, mission, engine);
  activeRun.begin(resume ? store.progress() : null);
}

class Run {
  constructor(game, mission, audio) {
    this.game = game;
    this.mission = mission;
    this.audio = audio;
    this.voices = new Voices(audio, mission.id);
    this.speech = new Speech();
    this.recorder = new Recorder();
    this.simulated = store.settings().mode === "sim";
    this.lastFix = null;
    this.speechGeneration = 0;
    this.voiceActive = false;
    this.micActive = false;
    this.completionRecorded = false;
    this.pocketMode = false;
    this.lastRenderAt = 0;
    this.simHeading = 0;
    this.wakeLock = null;
    this.timers = [];
    this.broadcastsSince = 0;

    const onFix = (fix) => this.handleFix(fix);
    this.gps = this.simulated ? null : new Gps(onFix);
    this.simulator = this.simulated ? new SimWalker(onFix, measureBetween) : null;
    this.compass = new Compass((degrees) =>
      this.game.phoneHeading(degrees, Date.now(), !this.pocketMode));
  }

  begin(savedProgress) {
    byId("sim").hidden = !this.simulated;
    this.applyEffects(this.game.start(
      Date.now(), savedProgress ? JSON.stringify(savedProgress) : null,
    ));
    if (this.simulated) {
      const state = JSON.parse(this.game.state());
      const target = this.mission.signals[state.signalIndex] ?? this.mission.signals[0];
      const [lat, lon] = step(target.location.lat, target.location.lon, 180, 90);
      this.simulator.start(lat, lon);
      this.game.phoneHeading(this.simHeading, Date.now(), true);
    } else {
      this.gps.start();
    }
    this.compass.start();
    this.timers.push(setInterval(() => this.tick(), 50));
    this.timers.push(setInterval(() => this.pollBroadcasts(), 10_000));
    this.broadcastsSince = Date.now();
    this.configureMediaSession(true);
    this.keepScreenAwake();
  }

  end() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.speechGeneration++;
    this.voices.stop();
    this.speech.stop();
    this.recorder.stop();
    this.gps?.stop();
    this.simulator?.stop();
    this.compass.stop();
    this.audio.stopMusic();
    this.audio.setTheme(null);
    this.audio.setComms("none");
    this.configureMediaSession(false);
    this.wakeLock?.release().catch(() => {});
    this.wakeLock = null;
    setPocketMode(false);
  }

  // -- engine loop ---------------------------------------------------------------

  tick() {
    const now = Date.now();
    this.applyEffects(this.game.tick(now));
    this.audio.setScene(JSON.parse(this.game.scene()));
    this.audio.setHeading(
      this.game.listenerHeading(now) ?? null,
      this.game.listenerPitch(now) ?? null,
    );
    if (now - this.lastRenderAt > 150) this.render();
  }

  applyEffects(effectsJson) {
    for (const effect of JSON.parse(effectsJson)) {
      try {
        this.executeEffect(effect);
      } catch (error) {
        console.error("effect failed", effect, error);
      }
    }
  }

  /** Carries out one engine effect, as the app's GameController does. */
  executeEffect(effect) {
    switch (effect.type) {
      case "speak":
        return this.speakLines(effect);
      case "stopSpeaking":
        this.speechGeneration++;
        this.voices.stop();
        this.voiceActive = false;
        this.updateComms();
        byId("speaking").hidden = true;
        return;
      case "sfx":
        return this.audio.sfx(effect.sfx, effect.bearingDeg);
      case "listen":
        return this.openListening(effect);
      case "stopListening":
        return this.speech.stop();
      case "ask":
        return this.askCharacter(effect);
      case "startRecording":
        this.micActive = true;
        this.updateComms();
        this.recorder.start(effect.signalId, effect.maxSeconds, (key) => {
          this.micActive = false;
          this.updateComms();
          this.applyEffects(this.game.recordingFinished(key));
        });
        return;
      case "stopRecording":
        return this.recorder.stop();
      case "rhythm":
        return this.audio.playRhythm(effect.onsetsBeats, () =>
          this.applyEffects(this.game.rhythmPlayed(effect.token, Date.now())));
      case "unlockStem":
        return; // The audio scene brings the stem in on the next bar.
      case "persist":
        return this.persistProgress(effect.progress);
      default:
        return;
    }
  }

  async speakLines(effect) {
    const generation = ++this.speechGeneration;
    this.voices.stop();
    this.voiceActive = true;
    this.updateComms();
    effect.utterances.forEach((u) => this.voices.prefetch(u));

    for (const utterance of effect.utterances) {
      if (generation !== this.speechGeneration) return;
      byId("speaking").hidden = false;
      byId("speaker").textContent = utterance.speakerName;
      byId("spoken").textContent = utterance.text;
      const bearing = utterance.fromLat != null && this.lastFix
        ? measureBetween(this.lastFix.lat, this.lastFix.lon, utterance.fromLat, utterance.fromLon)[1]
        : null;
      await this.voices.say(utterance, bearing);
    }

    if (generation !== this.speechGeneration) return;
    this.voiceActive = false;
    this.updateComms();
    byId("speaking").hidden = true;
    this.applyEffects(this.game.speechFinished(effect.token));
  }

  openListening(effect) {
    this.micActive = true;
    this.updateComms();
    this.audio.sfx("MIC_OPEN");
    byId("listening").hidden = false;
    byId("partial").textContent = this.speech.available
      ? "Listening…"
      : "Tap or type your answer";

    const hints = byId("hints");
    hints.textContent = "";
    for (const hint of effect.hints) {
      hints.append(createElement("button", {
        type: "button",
        text: hint,
        onclick: () => this.speech.answer(hint),
      }));
    }
    byId("typed").value = "";

    this.speech.listen(effect, {
      onText: (text, isFinal, confidence) => {
        byId("partial").textContent = text;
        this.applyEffects(this.game.heard(effect.token, text, isFinal, confidence));
      },
      onEnd: () => {
        this.micActive = false;
        this.updateComms();
        this.audio.sfx("MIC_CLOSE");
        byId("listening").hidden = true;
        this.applyEffects(this.game.listenEnded(effect.token));
      },
    });
  }

  async askCharacter(effect) {
    let reply = null;
    if (store.token()) {
      try {
        const timeout = new Promise((_, reject) =>
          setTimeout(() => reject(new Error("timeout")), NPC_TIMEOUT_MS));
        const result = await Promise.race([api.npc(effect.body), timeout]);
        reply = result.reply ?? null;
      } catch {
        reply = null; // The engine falls back to its written lines.
      }
    }
    // Answer asynchronously, as a real network reply would arrive.
    setTimeout(() =>
      this.applyEffects(this.game.characterReplied(effect.requestId, reply)), 0);
  }

  persistProgress(progress) {
    store.saveProgress(progress);
    if (!progress.complete || this.completionRecorded) return;
    this.completionRecorded = true;

    const rewards = store.rewards();
    const newlyUnlocked = JSON.parse(Engine.unlockedByNextCompletion(
      loaded.missionJson, loaded.catalogJson, rewards.completions,
    ));
    const nextRewards = {
      ...rewards,
      completions: rewards.completions + 1,
      soundtrack: newlyUnlocked.length
        ? newlyUnlocked[newlyUnlocked.length - 1].id
        : rewards.soundtrack,
    };
    store.saveRewards(nextRewards);

    if (newlyUnlocked.length) {
      byId("unlocked").hidden = false;
      byId("unlocked").textContent =
        `★ UNLOCKED · ${newlyUnlocked.map((u) => u.title).join(", ")} · plays on your next run`;
    }
    if (loaded.plan) api.progress(this.mission.id, nextRewards.completions).catch(() => {});
  }

  updateComms() {
    this.audio.setComms(this.micActive ? "mic" : this.voiceActive ? "voice" : "none");
  }

  // -- inputs -----------------------------------------------------------------------

  sendInput(action) {
    this.applyEffects(this.game.input(action, Date.now()));
    this.render();
  }

  handleFix(fix) {
    this.lastFix = fix;
    this.applyEffects(this.game.position(
      fix.lat, fix.lon, fix.accuracyM, fix.timeMs,
      fix.speedMps, fix.courseDeg, fix.simulated,
    ));
  }

  async pollBroadcasts() {
    try {
      const { broadcasts } = await api.broadcasts(this.broadcastsSince);
      const ordered = [...broadcasts].sort((a, b) => a.createdAtMs - b.createdAtMs);
      for (const broadcast of ordered) {
        this.applyEffects(this.game.broadcast(broadcast.speaker, broadcast.text));
        this.broadcastsSince = Math.max(this.broadcastsSince, broadcast.createdAtMs);
      }
    } catch {
      // Offline for a moment; the next poll retries.
    }
  }

  /** Glasses touchpad taps arrive as media keys: tap=capture, next=hint, prev=repeat. */
  configureMediaSession(enabled) {
    if (!("mediaSession" in navigator)) return;
    const mediaSession = navigator.mediaSession;
    const handlers = { play: "SCAN", pause: "SCAN", nexttrack: "HINT", previoustrack: "REPEAT" };
    for (const [eventName, action] of Object.entries(handlers)) {
      try {
        mediaSession.setActionHandler(eventName, enabled
          ? () => {
              this.sendInput(action);
              this.audio.element?.play().catch(() => {});
              mediaSession.playbackState = "playing";
            }
          : null);
      } catch {
        // This browser does not support that media action.
      }
    }
    if (enabled) {
      mediaSession.metadata = new MediaMetadata({
        title: "GhostSignal",
        artist: "GHOST 93.3",
        album: this.mission.music.title || "The Last Eight Bars",
      });
      mediaSession.playbackState = "playing";
    } else {
      mediaSession.playbackState = "none";
    }
  }

  async keepScreenAwake() {
    try {
      if ("wakeLock" in navigator) {
        this.wakeLock = await navigator.wakeLock.request("screen");
      }
    } catch {
      // Not allowed (battery saver, no gesture yet): the screen may dim.
    }
  }

  // -- screen -------------------------------------------------------------------------

  render() {
    this.lastRenderAt = Date.now();
    const state = JSON.parse(this.game.state());

    byId("phase").textContent = state.paused ? "PAUSED" : state.phase;
    byId("count").textContent = state.phase === "COMPLETE"
      ? `${state.total} OF ${state.total}`
      : `SIGNAL ${Math.min(state.signalIndex + 1, state.total)} OF ${state.total}`;
    byId("objective").textContent = state.objective;

    const distance = state.distanceM;
    byId("distance").textContent = distance == null
      ? "—"
      : distance < 12 ? "here" : `${Math.round(distance)} m`;
    byId("direction").textContent = state.direction ??
      (this.lastFix || this.simulated ? "" : "waiting for GPS…");
    byId("accuracy").textContent = this.lastFix
      ? `GPS ±${Math.round(this.lastFix.accuracyM)} m${this.lastFix.simulated ? " · simulated" : ""}`
      : "";

    const relativeBearing = state.bearingDeg == null
      ? 0
      : state.bearingDeg - (state.headingDeg ?? 0);
    byId("arrow").style.transform = `rotate(${relativeBearing}deg)`;
    byId("arrow").style.opacity = state.bearingDeg == null ? "0.2" : "1";
    byId("capture").classList.toggle("ready", state.lockAvailable);
    byId("pauseBtn").textContent = state.paused ? "Resume" : "Pause";
    byId("pocketStatus").textContent =
      `${state.objective}${distance != null ? ` · ${Math.round(distance)} m` : ""}`;

    this.renderLayers(state);
    this.renderTranscript(state);
    if (state.phase === "COMPLETE") byId("stop").textContent = "Done";
  }

  renderLayers(state) {
    const list = byId("layers");
    if (list.childElementCount !== state.layers.length) {
      list.textContent = "";
      state.layers.forEach(() =>
        list.append(createElement("li", {}, createElement("b"), createElement("span"))));
    }
    state.layers.forEach((layer, index) => {
      const item = list.children[index];
      item.className = layer.collected
        ? "got"
        : index === state.signalIndex && state.phase !== "COMPLETE" ? "now" : "";
      item.querySelector("b").textContent = String(index + 1).padStart(2, "0");
      // Layers are named only once found: discovery, not a spoiler list.
      item.querySelector("span").textContent = layer.collected
        ? layer.name
        : index === state.signalIndex ? (state.signalName ?? "") : "";
    });
  }

  renderTranscript(state) {
    const list = byId("transcript");
    list.textContent = "";
    for (const entry of state.transcript) {
      const speakerName = entry.fromPlayer
        ? "YOU"
        : this.mission.characters.find((c) => c.id === entry.speaker)?.name ?? entry.speaker;
      list.append(createElement(
        "li",
        { class: entry.fromPlayer ? "you" : "" },
        createElement("b", { text: speakerName.toUpperCase() }),
        entry.text,
      ));
    }
  }
}

// -- buttons, keys, pocket mode -------------------------------------------------

/** Tap = capture, hold = talk: big button, pocket overlay, and space bar. */
function makeHoldable(node, { onPress = () => {}, onRelease = () => {} } = {}) {
  let holdTimer = null;
  let wasHeld = false;

  node.addEventListener("pointerdown", (event) => {
    if (!activeRun || event.button > 0) return;
    event.preventDefault();
    wasHeld = false;
    onPress();
    holdTimer = setTimeout(() => {
      wasHeld = true;
      holdTimer = null;
      activeRun.sendInput("TALK");
    }, HOLD_TO_TALK_MS);
  });

  const release = () => {
    onRelease();
    if (!holdTimer) return;
    clearTimeout(holdTimer);
    holdTimer = null;
    if (!wasHeld && activeRun) activeRun.sendInput("SCAN");
  };
  node.addEventListener("pointerup", release);
  node.addEventListener("pointercancel", () => {
    onRelease();
    clearTimeout(holdTimer);
    holdTimer = null;
  });
  node.addEventListener("contextmenu", (event) => event.preventDefault());
}

makeHoldable(byId("capture"), {
  onPress: () => byId("capture").classList.add("held"),
  onRelease: () => byId("capture").classList.remove("held"),
});
makeHoldable(byId("pocketOverlay"), {
  onPress: () => byId("pocketOverlay").classList.add("held"),
  onRelease: () => byId("pocketOverlay").classList.remove("held"),
});

document.querySelectorAll("[data-action]").forEach((button) =>
  button.addEventListener("click", () => activeRun?.sendInput(button.dataset.action)));

let spacePressedAt = 0;
let spaceTimer = null;
document.addEventListener("keydown", (event) => {
  if (!activeRun || event.repeat || event.target.tagName === "INPUT") return;
  const keyActions = { h: "HINT", r: "REPEAT", s: "STATUS", p: "PAUSE_TOGGLE" };
  if (event.code === "Space") {
    event.preventDefault();
    spacePressedAt = Date.now();
    spaceTimer = setTimeout(() => {
      spaceTimer = null;
      activeRun?.sendInput("TALK");
    }, HOLD_TO_TALK_MS);
  } else if (keyActions[event.key]) {
    activeRun.sendInput(keyActions[event.key]);
  }
});
document.addEventListener("keyup", (event) => {
  if (!activeRun || event.code !== "Space" || event.target.tagName === "INPUT") return;
  if (spaceTimer && Date.now() - spacePressedAt < HOLD_TO_TALK_MS) {
    clearTimeout(spaceTimer);
    spaceTimer = null;
    activeRun.sendInput("SCAN");
  }
});

byId("typeForm").addEventListener("submit", (event) => {
  event.preventDefault();
  activeRun?.speech.answer(byId("typed").value);
});

function setPocketMode(on) {
  if (activeRun) activeRun.pocketMode = on;
  byId("pocketOverlay").hidden = !on;
  if (on && activeRun) activeRun.keepScreenAwake();
}
byId("pocket").addEventListener("click", () => setPocketMode(true));
byId("pocketExit").addEventListener("pointerdown", (event) => event.stopPropagation());
byId("pocketExit").addEventListener("click", (event) => {
  event.stopPropagation();
  setPocketMode(false);
});

byId("stop").addEventListener("click", () => {
  if (!activeRun) return;
  const finished = JSON.parse(activeRun.game.state()).phase === "COMPLETE";
  if (!finished && !confirm("End this run? Your progress is saved; you can resume it.")) return;
  activeRun.end();
  activeRun = null;
  byId("run").hidden = true;
  byId("unlocked").hidden = true;
  byId("stop").textContent = "End run";
  renderSetup();
  byId("setup").hidden = false;
});

// Simulated-walk controls (desk mode).
function currentSimTarget() {
  const state = JSON.parse(activeRun.game.state());
  return activeRun.mission.signals[state.signalIndex];
}
byId("simWalk").addEventListener("click", () => {
  const target = currentSimTarget();
  if (target) activeRun.simulator.walkTo(target.location.lat, target.location.lon, 1.6);
});
byId("simRun").addEventListener("click", () => {
  const target = currentSimTarget();
  if (target) activeRun.simulator.walkTo(target.location.lat, target.location.lon, 6);
});
byId("simArrive").addEventListener("click", () => {
  const target = currentSimTarget();
  if (target) activeRun.simulator.teleport(target.location.lat, target.location.lon);
});
byId("simSolve").addEventListener("click", () =>
  activeRun.applyEffects(activeRun.game.forceSolve()));
const turnSimulated = (deltaDegrees) => {
  activeRun.simHeading = (activeRun.simHeading + deltaDegrees + 360) % 360;
  activeRun.game.phoneHeading(activeRun.simHeading, Date.now(), true);
};
byId("simLeft").addEventListener("click", () => turnSimulated(-30));
byId("simRight").addEventListener("click", () => turnSimulated(30));

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible" || !activeRun) return;
  activeRun.keepScreenAwake();
  audioEngine?.resume().catch(() => {});
});

boot();
