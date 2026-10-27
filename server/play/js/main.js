/**
 * GhostSignal in the browser. The same game engine as the Android app (compiled from core/ to
 * JavaScript), driven the way the app's GameController drives it: sensors and buttons in as
 * events, effects out to the audio, voices, microphone and screen.
 *
 * The phone runs everything; the glasses are its Bluetooth speakers, microphone and buttons.
 */
import * as Engine from "../engine/ghostsignal-core.mjs";
import { api, store } from "./api.js";
import { AudioEngine } from "./audio.js";
import { Recorder } from "./recorder.js";
import { Compass, Gps, SimWalker, step } from "./sensors.js";
import { Speech } from "./speech.js";
import { Voices } from "./voices.js";

const $ = (id) => document.getElementById(id);
const HOLD_MS = 450;
const base = { missionJson: null, mission: null, routeJson: null, catalog: null, catalogJson: null, plan: null };
let audio = null;
let run = null;

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "text") node.textContent = v;
    else if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children) if (c !== null && c !== undefined) node.append(c);
  return node;
}

const measure = (lat1, lon1, lat2, lon2) => Engine.distanceAndBearing(lat1, lon1, lat2, lon2);

function ensureAudio() {
  if (!audio) {
    audio = new AudioEngine();
    audio.setVolume(store.settings().volume);
  }
  return audio;
}

// ==================================================================== setup

async function boot() {
  try {
    const mission = await api.mission();
    base.mission = mission;
    base.missionJson = JSON.stringify(mission);
    const [route, catalog, plan] = await Promise.all([
      api.route(mission.id).catch(() => null),
      api.tracks(mission.id).catch(() => null),
      api.me(),
    ]);
    base.routeJson = route ? JSON.stringify(route) : null;
    base.catalog = catalog;
    base.catalogJson = catalog ? JSON.stringify(catalog) : null;
    base.plan = plan;
    // Finished runs follow a signed-in runner: take the account's count if it's higher.
    const r = store.rewards();
    const remote = plan?.account?.completions?.[mission.id] ?? 0;
    if (remote > r.completions) store.saveRewards({ ...r, completions: remote });
    else if (plan && r.completions > remote) api.progress(mission.id, r.completions).catch(() => {});
    $("loading").hidden = true;
    renderSetup();
    $("setup").hidden = false;
  } catch (e) {
    $("loading").hidden = true;
    $("fatal").hidden = false;
    $("fatal").textContent = `Couldn't reach the GhostSignal server: ${e.message}`;
  }
}

function renderSetup() {
  const plan = base.plan;
  const who = $("who");
  who.textContent = plan ? plan.account.email : "Sign in";
  who.href = plan ? "/account/" : "/account/?return=/play/";

  const rewards = store.rewards();
  const list = $("soundtracks");
  list.textContent = "";
  const choices = JSON.parse(Engine.soundtrackChoices(base.missionJson, base.catalogJson, rewards.completions));
  const picked = choices.find((c) => c.id === rewards.soundtrack && c.unlocked) ? rewards.soundtrack : "original";
  for (const c of choices) {
    const runs = c.unlockAfterCompletions === 1 ? "a run" : `${c.unlockAfterCompletions} runs`;
    const input = el("input", { type: "radio", name: "soundtrack", value: c.id, disabled: !c.unlocked, checked: c.id === picked });
    input.addEventListener("change", () => store.saveRewards({ ...store.rewards(), soundtrack: c.id }));
    list.append(
      el("label", { class: c.unlocked ? "choice" : "choice locked" }, input,
        el("span", {}, el("b", { text: c.title }), c.unlocked ? c.description : `Finish ${runs} to unlock`)),
    );
  }
  for (const t of base.catalog?.locked ?? []) {
    list.append(
      el("label", { class: "choice locked" }, el("input", { type: "radio", disabled: true }),
        el("span", {}, el("b", {}, t.title, el("span", { class: "tag", text: "PREMIUM" })), t.description || "For premium players")),
    );
  }
  const unlockedCount = choices.filter((c) => c.unlocked).length;
  $("soundtrackNote").textContent = base.catalog?.locked?.length
    ? plan ? "Premium soundtracks come with premium, on the account page." : "Sign in for premium soundtracks."
    : unlockedCount < choices.length ? "Finish runs to unlock more." : "";

  const settings = store.settings();
  document.querySelectorAll('input[name="mode"]').forEach((i) => {
    i.checked = i.value === settings.mode;
    i.onchange = () => store.saveSettings({ ...store.settings(), mode: i.value });
  });

  $("accountText").textContent = plan
    ? `Signed in as ${plan.account.email}${plan.account.premium.active ? " · premium" : ""}. Characters speak in their own voices and answer your questions live; your finished runs follow you.`
    : "Signed out: this browser reads the lines in its own voice, and characters answer with written lines. Sign in for their real voices, live answers and premium soundtracks.";

  const progress = store.progress();
  $("resume").hidden = !(progress && !progress.complete && progress.missionId === base.mission.id && progress.collected.length > 0);
  $("micNote").textContent = new Speech().available
    ? "To answer out loud. You can always tap the answer instead."
    : "This browser can't recognise speech, so you'll tap or type your answers.";
}

function markCheck(id, ok, note) {
  const li = $(id);
  li.classList.toggle("done", ok === true);
  li.classList.toggle("warn", ok === false);
  if (note) li.querySelector("span").textContent = note;
}

$("soundCheck").addEventListener("click", async () => {
  const a = ensureAudio();
  await a.resume();
  a.soundCheck();
  markCheck("checkAudio", true, "A ping went round your head: left, behind, right, ahead. If it came from the phone, connect the glasses and try again.");
});

$("allowLocation").addEventListener("click", async () => {
  try {
    const acc = await Gps.check();
    markCheck("checkLocation", true, `Location on, accurate to about ${Math.round(acc)} m.`);
  } catch (e) {
    markCheck("checkLocation", false, `${e.message[0].toUpperCase()}${e.message.slice(1)}. You can still try it at a desk.`);
  }
});

$("allowCompass").addEventListener("click", async () => {
  try {
    await Compass.permit();
    let got = false;
    const c = new Compass((deg) => {
      got = true;
      markCheck("checkCompass", true, `Compass on: facing ${Math.round(deg)}°.`);
    });
    c.start();
    setTimeout(() => {
      c.stop();
      if (!got) markCheck("checkCompass", false, "No compass reading. The game will use your walking direction instead.");
    }, 2500);
  } catch (e) {
    markCheck("checkCompass", false, `${e.message[0].toUpperCase()}${e.message.slice(1)}. The game will use your walking direction instead.`);
  }
});

$("allowMic").addEventListener("click", async () => {
  try {
    const s = await navigator.mediaDevices.getUserMedia({ audio: true });
    s.getTracks().forEach((t) => t.stop());
    markCheck("checkMic", true, new Speech().available ? "Microphone on. Answer out loud, or tap." : "Microphone on (for the Record challenge). Tap or type your answers.");
  } catch {
    markCheck("checkMic", false, "Microphone blocked. Tap or type your answers instead.");
  }
});

$("start").addEventListener("click", () => startRun(false));
$("resume").addEventListener("click", () => startRun(true));

// ==================================================================== a run

async function startRun(resume) {
  const a = ensureAudio();
  await a.resume(); // inside the tap, so the browser lets sound start
  $("setup").hidden = true;
  $("run").hidden = false;
  $("objective").textContent = "Loading the soundtrack…";
  const rewards = store.rewards();
  const game = new Engine.WebGame(base.missionJson, base.routeJson, base.catalogJson, rewards.soundtrack, rewards.completions);
  const mission = JSON.parse(game.missionJson());
  const buffers = new Map();
  await Promise.all(
    mission.music.stems.map(async (s) => {
      try {
        buffers.set(s.id, await a.decode(await api.audio(s.file)));
      } catch (e) {
        console.warn(`stem ${s.file} didn't load`, e);
      }
    }),
  );
  let theme = null;
  if (mission.antagonist?.theme) {
    try {
      theme = await a.decode(await api.audio(mission.antagonist.theme));
    } catch (e) {
      console.warn("theme didn't load", e);
    }
  }
  a.startMusic(mission.music, buffers);
  a.setTheme(theme, mission.antagonist?.gainDb ?? 0);
  run = new Run(game, mission, a);
  run.begin(resume ? store.progress() : null);
}

class Run {
  constructor(game, mission, a) {
    this.game = game;
    this.mission = mission;
    this.audio = a;
    this.voices = new Voices(a, mission.id);
    this.speech = new Speech();
    this.recorder = new Recorder();
    this.simulated = store.settings().mode === "sim";
    this.lastFix = null;
    this.speakSeq = 0;
    this.voiceActive = false;
    this.micActive = false;
    this.completionRecorded = false;
    this.pocket = false;
    this.lastUi = 0;
    this.simHeading = 0;
    this.wakeLock = null;
    this.timers = [];
    const onFix = (fix) => this.onFix(fix);
    this.gps = this.simulated ? null : new Gps(onFix);
    this.sim = this.simulated ? new SimWalker(onFix, measure) : null;
    this.compass = new Compass((deg) => this.game.phoneHeading(deg, Date.now(), !this.pocket));
  }

  begin(progress) {
    $("sim").hidden = !this.simulated;
    this.apply(this.game.start(Date.now(), progress ? JSON.stringify(progress) : null));
    if (this.simulated) {
      const s = JSON.parse(this.game.state());
      const target = this.mission.signals[s.signalIndex] ?? this.mission.signals[0];
      const [lat, lon] = step(target.location.lat, target.location.lon, 180, 90);
      this.sim.start(lat, lon);
      this.game.phoneHeading(this.simHeading, Date.now(), true);
    } else {
      this.gps.start();
    }
    this.compass.start();
    this.timers.push(setInterval(() => this.tick(), 50));
    this.timers.push(setInterval(() => this.pollBroadcasts(), 10_000));
    this.since = Date.now();
    this.mediaSession(true);
    this.keepAwake();
  }

  end() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.speakSeq++;
    this.voices.stop();
    this.speech.stop();
    this.recorder.stop();
    this.gps?.stop();
    this.sim?.stop();
    this.compass.stop();
    this.audio.stopMusic();
    this.audio.setTheme(null);
    this.audio.setComms("none");
    this.mediaSession(false);
    this.wakeLock?.release().catch(() => {});
    this.wakeLock = null;
    setPocket(false);
  }

  // ---------------------------------------------------------------- engine loop

  tick() {
    const now = Date.now();
    this.apply(this.game.tick(now));
    this.audio.setScene(JSON.parse(this.game.scene()));
    this.audio.setHeading(this.game.listenerHeading(now) ?? null);
    if (now - this.lastUi > 150) this.render();
  }

  apply(json) {
    for (const e of JSON.parse(json)) {
      try {
        this.execute(e);
      } catch (err) {
        console.error("effect failed", e, err);
      }
    }
  }

  /** Each effect, as the app's GameController carries it out. */
  execute(e) {
    switch (e.type) {
      case "speak":
        return this.speak(e);
      case "stopSpeaking":
        this.speakSeq++;
        this.voices.stop();
        this.voiceActive = false;
        this.comms();
        $("speaking").hidden = true;
        return;
      case "sfx":
        return this.audio.sfx(e.sfx, e.bearingDeg);
      case "listen":
        return this.listen(e);
      case "stopListening":
        return this.speech.stop();
      case "ask":
        return this.ask(e);
      case "startRecording":
        this.micActive = true;
        this.comms();
        this.recorder.start(e.signalId, e.maxSeconds, (key) => {
          this.micActive = false;
          this.comms();
          this.apply(this.game.recordingFinished(key));
        });
        return;
      case "stopRecording":
        return this.recorder.stop();
      case "rhythm":
        return this.audio.playRhythm(e.onsetsBeats, () => this.apply(this.game.rhythmPlayed(e.token, Date.now())));
      case "unlockStem":
        return; // the audio scene brings it in on the next bar
      case "persist":
        return this.persist(e.progress);
    }
  }

  async speak(e) {
    const id = ++this.speakSeq;
    this.voices.stop();
    this.voiceActive = true;
    this.comms();
    e.utterances.forEach((u) => this.voices.prefetch(u));
    for (const u of e.utterances) {
      if (id !== this.speakSeq) return;
      $("speaking").hidden = false;
      $("speaker").textContent = u.speakerName;
      $("spoken").textContent = u.text;
      const bearing = u.fromLat != null && this.lastFix ? measure(this.lastFix.lat, this.lastFix.lon, u.fromLat, u.fromLon)[1] : null;
      await this.voices.say(u, bearing);
    }
    if (id !== this.speakSeq) return;
    this.voiceActive = false;
    this.comms();
    $("speaking").hidden = true;
    this.apply(this.game.speechFinished(e.token));
  }

  listen(e) {
    this.micActive = true;
    this.comms();
    this.audio.sfx("MIC_OPEN");
    $("listening").hidden = false;
    $("partial").textContent = this.speech.available ? "Listening…" : "Tap or type your answer";
    const hints = $("hints");
    hints.textContent = "";
    for (const h of e.hints) hints.append(el("button", { type: "button", text: h, onclick: () => this.speech.answer(h) }));
    $("typed").value = "";
    this.speech.listen(e, {
      onText: (text, isFinal, confidence) => {
        $("partial").textContent = text;
        this.apply(this.game.heard(e.token, text, isFinal, confidence));
      },
      onEnd: () => {
        this.micActive = false;
        this.comms();
        this.audio.sfx("MIC_CLOSE");
        $("listening").hidden = true;
        this.apply(this.game.listenEnded(e.token));
      },
    });
  }

  async ask(e) {
    let reply = null;
    if (store.token()) {
      try {
        const res = await Promise.race([api.npc(e.body), new Promise((_, no) => setTimeout(() => no(new Error("timeout")), 10_000))]);
        reply = res.reply ?? null;
      } catch {
        reply = null; // the engine falls back to written lines
      }
    }
    // Never answer inside the effect that asked: the engine expects it later, like a network reply.
    setTimeout(() => this.apply(this.game.characterReplied(e.requestId, reply)), 0);
  }

  persist(progress) {
    store.saveProgress(progress);
    if (!progress.complete || this.completionRecorded) return;
    this.completionRecorded = true;
    const r = store.rewards();
    const unlocked = JSON.parse(Engine.unlockedByNextCompletion(base.missionJson, base.catalogJson, r.completions));
    const next = { ...r, completions: r.completions + 1, soundtrack: unlocked.length ? unlocked[unlocked.length - 1].id : r.soundtrack };
    store.saveRewards(next);
    if (unlocked.length) {
      $("unlocked").hidden = false;
      $("unlocked").textContent = `★ UNLOCKED · ${unlocked.map((u) => u.title).join(", ")} · plays on your next run`;
    }
    if (base.plan) api.progress(this.mission.id, next.completions).catch(() => {});
  }

  comms() {
    this.audio.setComms(this.micActive ? "mic" : this.voiceActive ? "voice" : "none");
  }

  // ---------------------------------------------------------------- inputs

  input(action) {
    this.apply(this.game.input(action, Date.now()));
    this.render();
  }

  onFix(fix) {
    this.lastFix = fix;
    this.apply(this.game.position(fix.lat, fix.lon, fix.accuracyM, fix.timeMs, fix.speedMps, fix.courseDeg, fix.simulated));
  }

  async pollBroadcasts() {
    try {
      const { broadcasts } = await api.broadcasts(this.since);
      for (const b of broadcasts.sort((x, y) => x.createdAtMs - y.createdAtMs)) {
        this.apply(this.game.broadcast(b.speaker, b.text));
        this.since = Math.max(this.since, b.createdAtMs);
      }
    } catch {
      // offline for a moment; try again next time
    }
  }

  /** The glasses' touchpad arrives as media keys: tap = capture, next = hint, previous = repeat. */
  mediaSession(on) {
    if (!("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const handlers = { play: "SCAN", pause: "SCAN", nexttrack: "HINT", previoustrack: "REPEAT" };
    for (const [event, action] of Object.entries(handlers)) {
      try {
        ms.setActionHandler(event, on ? () => {
          this.input(action);
          this.audio.element?.play().catch(() => {});
          ms.playbackState = "playing";
        } : null);
      } catch {
        // this browser doesn't support that action
      }
    }
    if (on) {
      ms.metadata = new MediaMetadata({ title: "GhostSignal", artist: "GHOST 93.3", album: this.mission.music.title || "The Last Eight Bars" });
      ms.playbackState = "playing";
    } else {
      ms.playbackState = "none";
    }
  }

  async keepAwake() {
    try {
      if ("wakeLock" in navigator) this.wakeLock = await navigator.wakeLock.request("screen");
    } catch {
      // not allowed (battery saver, or no tap yet): the screen may dim
    }
  }

  // ---------------------------------------------------------------- the screen

  render() {
    this.lastUi = Date.now();
    const s = JSON.parse(this.game.state());
    $("phase").textContent = s.paused ? "PAUSED" : s.phase;
    $("count").textContent = s.phase === "COMPLETE" ? "8 OF 8" : `SIGNAL ${Math.min(s.signalIndex + 1, s.total)} OF ${s.total}`;
    $("objective").textContent = s.objective;
    const near = s.distanceM;
    $("distance").textContent = near == null ? "—" : near < 12 ? "here" : `${Math.round(near)} m`;
    $("direction").textContent = s.direction ?? (this.lastFix ? "" : this.simulated ? "" : "waiting for GPS…");
    $("accuracy").textContent = this.lastFix ? `GPS ±${Math.round(this.lastFix.accuracyM)} m${this.lastFix.simulated ? " · simulated" : ""}` : "";
    const rel = s.bearingDeg == null ? 0 : s.bearingDeg - (s.headingDeg ?? 0);
    $("arrow").style.transform = `rotate(${rel}deg)`;
    $("arrow").style.opacity = s.bearingDeg == null ? "0.2" : "1";
    $("capture").classList.toggle("ready", s.lockAvailable);
    $("pauseBtn").textContent = s.paused ? "Resume" : "Pause";
    $("pocketStatus").textContent = `${s.objective}${near != null ? ` · ${Math.round(near)} m` : ""}`;

    const layers = $("layers");
    if (layers.childElementCount !== s.layers.length) {
      layers.textContent = "";
      s.layers.forEach(() => layers.append(el("li", {}, el("b"), el("span"))));
    }
    s.layers.forEach((l, i) => {
      const li = layers.children[i];
      li.className = l.collected ? "got" : i === s.signalIndex && s.phase !== "COMPLETE" ? "now" : "";
      li.querySelector("b").textContent = String(i + 1).padStart(2, "0");
      // A layer is named once it's found: discovery, not a spoiler list.
      li.querySelector("span").textContent = l.collected ? l.name : i === s.signalIndex ? (s.signalName ?? "") : "";
    });

    const t = $("transcript");
    t.textContent = "";
    for (const line of s.transcript) {
      const who = line.fromPlayer ? "YOU" : this.mission.characters.find((c) => c.id === line.speaker)?.name ?? line.speaker;
      t.append(el("li", { class: line.fromPlayer ? "you" : "" }, el("b", { text: who.toUpperCase() }), line.text));
    }
    if (s.phase === "COMPLETE") $("stop").textContent = "Done";
  }
}

// ==================================================================== buttons and keys

/** Tap for capture, hold for talk: on the big button, in pocket mode, and on the space bar. */
function holdable(node, { onPress = () => {}, onRelease = () => {} } = {}) {
  let timer = null;
  let held = false;
  node.addEventListener("pointerdown", (e) => {
    if (!run || e.button > 0) return;
    e.preventDefault();
    held = false;
    onPress();
    timer = setTimeout(() => {
      held = true;
      timer = null;
      run.input("TALK");
    }, HOLD_MS);
  });
  const up = () => {
    onRelease();
    if (timer) {
      clearTimeout(timer);
      timer = null;
      if (!held && run) run.input("SCAN");
    }
  };
  node.addEventListener("pointerup", up);
  node.addEventListener("pointercancel", () => {
    onRelease();
    clearTimeout(timer);
    timer = null;
  });
  node.addEventListener("contextmenu", (e) => e.preventDefault());
}

holdable($("capture"), { onPress: () => $("capture").classList.add("held"), onRelease: () => $("capture").classList.remove("held") });
holdable($("pocketOverlay"), { onPress: () => $("pocketOverlay").classList.add("held"), onRelease: () => $("pocketOverlay").classList.remove("held") });

document.querySelectorAll("[data-action]").forEach((b) => b.addEventListener("click", () => run?.input(b.dataset.action)));

let spaceAt = 0;
let spaceTimer = null;
document.addEventListener("keydown", (e) => {
  if (!run || e.repeat || e.target.tagName === "INPUT") return;
  const keys = { h: "HINT", r: "REPEAT", s: "STATUS", p: "PAUSE_TOGGLE" };
  if (e.code === "Space") {
    e.preventDefault();
    spaceAt = Date.now();
    spaceTimer = setTimeout(() => {
      spaceTimer = null;
      run?.input("TALK");
    }, HOLD_MS);
  } else if (keys[e.key]) run.input(keys[e.key]);
});
document.addEventListener("keyup", (e) => {
  if (!run || e.code !== "Space" || e.target.tagName === "INPUT") return;
  if (spaceTimer && Date.now() - spaceAt < HOLD_MS) {
    clearTimeout(spaceTimer);
    spaceTimer = null;
    run.input("SCAN");
  }
});

$("typeForm").addEventListener("submit", (e) => {
  e.preventDefault();
  run?.speech.answer($("typed").value);
});

function setPocket(on) {
  if (run) run.pocket = on;
  $("pocketOverlay").hidden = !on;
  if (on && run) run.keepAwake();
}
$("pocket").addEventListener("click", () => setPocket(true));
$("pocketExit").addEventListener("pointerdown", (e) => e.stopPropagation());
$("pocketExit").addEventListener("click", (e) => {
  e.stopPropagation();
  setPocket(false);
});

$("stop").addEventListener("click", () => {
  if (!run) return;
  const done = JSON.parse(run.game.state()).phase === "COMPLETE";
  if (!done && !confirm("End this run? Your progress is saved; you can resume it.")) return;
  run.end();
  run = null;
  $("run").hidden = true;
  $("unlocked").hidden = true;
  $("stop").textContent = "End run";
  renderSetup();
  $("setup").hidden = false;
});

// The simulated walk.
function simTarget() {
  const s = JSON.parse(run.game.state());
  return run.mission.signals[s.signalIndex];
}
$("simWalk").addEventListener("click", () => { const t = simTarget(); if (t) run.sim.walkTo(t.location.lat, t.location.lon, 1.6); });
$("simRun").addEventListener("click", () => { const t = simTarget(); if (t) run.sim.walkTo(t.location.lat, t.location.lon, 6); });
$("simArrive").addEventListener("click", () => { const t = simTarget(); if (t) run.sim.teleport(t.location.lat, t.location.lon); });
const turn = (d) => {
  run.simHeading = (run.simHeading + d + 360) % 360;
  run.game.phoneHeading(run.simHeading, Date.now(), true);
};
$("simSolve").addEventListener("click", () => run.apply(run.game.forceSolve()));
$("simLeft").addEventListener("click", () => turn(-30));
$("simRight").addEventListener("click", () => turn(30));

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible" || !run) return;
  run.keepAwake();
  audio?.resume().catch(() => {});
});

boot();
