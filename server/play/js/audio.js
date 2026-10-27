/**
 * The web player's audio: the browser's counterpart of the app's binaural mixer, on the Web Audio
 * API. Positioned sounds go through HRTF panners, so with the glasses (or any headphones) on, a
 * signal sits where it is in the park and stays there as you turn.
 *
 *   stems ──┬─ in-head ─────────────┐
 *           └─ clarity filter ─ HRTF ┴─ music bus ─┐
 *   HUSH's theme ──────────────────── music bus    ├─ master ─ limiter ─ output
 *   static, ping, sfx ─ HRTF ──────────────────────┤
 *   voices ─ radio filter ─ (HRTF) ─ voice bus ────┘
 *
 * Output goes through an <audio> element when the browser allows it, rather than straight to the
 * speakers: that makes the page a media player, so the glasses' touchpad taps (play/pause, next,
 * previous) reach it through the Media Session API.
 */

const DB = (db) => Math.pow(10, db / 20);
const PAUSED_FACTOR = 0.2;

export class AudioEngine {
  constructor() {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    this.ctx = new Ctx({ latencyHint: "playback" });
    const ctx = this.ctx;
    this.master = ctx.createGain();
    this.limiter = ctx.createDynamicsCompressor();
    this.limiter.threshold.value = -6;
    this.limiter.ratio.value = 12;
    this.limiter.attack.value = 0.003;
    this.limiter.release.value = 0.2;
    this.master.connect(this.limiter);
    this.musicBus = this.bus(this.master);
    this.voiceBus = this.bus(this.master);
    this.sfxBus = this.bus(this.master);
    this.heading = null;
    this.stems = new Map();
    this.loopSeconds = 0;
    this.barSeconds = 0;
    this.t0 = 0;
    this.comms = "none";
    this.paused = false;
    this.nextPingAt = 0;
    this.element = null;
    this.makeStatic();
    this.connectOutput();
  }

  bus(to) {
    const g = this.ctx.createGain();
    g.connect(to);
    return g;
  }

  /** Through an <audio> element when possible (see above); straight to the speakers otherwise. */
  connectOutput() {
    if (typeof this.ctx.createMediaStreamDestination === "function" && "srcObject" in HTMLMediaElement.prototype) {
      this.streamOut = this.ctx.createMediaStreamDestination();
      this.limiter.connect(this.streamOut);
      this.element = new Audio();
      this.element.srcObject = this.streamOut.stream;
      this.element.setAttribute("playsinline", "");
    } else {
      this.limiter.connect(this.ctx.destination);
    }
  }

  /** Call from a tap: browsers only start sound after the user asks for it. */
  async resume() {
    if (this.ctx.state !== "running") await this.ctx.resume();
    if (this.element) {
      try {
        await this.element.play();
      } catch {
        // The element refused (an old browser): fall back to the speakers directly.
        this.limiter.disconnect();
        this.limiter.connect(this.ctx.destination);
        this.element = null;
      }
    }
  }

  get now() {
    return this.ctx.currentTime;
  }

  setVolume(v) {
    this.master.gain.setTargetAtTime(Math.max(0, Math.min(1, v)), this.now, 0.05);
  }

  async decode(arrayBuffer) {
    return await this.ctx.decodeAudioData(arrayBuffer);
  }

  /** Exactly [frames] long: cut, or padded with silence, so every stem loops on the same sample. */
  fit(buffer, frames) {
    if (buffer.length === frames) return buffer;
    const out = this.ctx.createBuffer(buffer.numberOfChannels, frames, buffer.sampleRate);
    for (let c = 0; c < buffer.numberOfChannels; c++) out.copyToChannel(buffer.getChannelData(c).subarray(0, Math.min(frames, buffer.length)), c);
    return out;
  }

  // ------------------------------------------------------------------ music

  /** [buffers]: stem id -> AudioBuffer. Starts every stem silent and sample-locked. */
  startMusic(music, buffers) {
    this.stopMusic();
    const sr = this.ctx.sampleRate;
    const beatSeconds = 60 / music.bpm;
    this.beatSeconds = beatSeconds;
    this.barSeconds = beatSeconds * (music.beatsPerBar || 4);
    this.loopSeconds = this.barSeconds * (music.loopBars || 8);
    const frames = Math.round(this.loopSeconds * sr);
    this.t0 = this.now + 0.15;
    for (const spec of music.stems) {
      const buffer = buffers.get(spec.id);
      if (!buffer) continue;
      const source = this.ctx.createBufferSource();
      source.buffer = this.fit(buffer, frames);
      source.loop = true;
      const gain = this.ctx.createGain();
      gain.gain.value = 0;
      const trim = DB(spec.gainDb || 0);
      // Far away a stem is muffled and thin; close in it opens up.
      const low = this.ctx.createBiquadFilter();
      low.type = "lowpass";
      low.frequency.value = 600;
      const high = this.ctx.createBiquadFilter();
      high.type = "highpass";
      high.frequency.value = 20;
      const inHead = this.ctx.createGain();
      const located = this.ctx.createGain();
      const panner = this.panner();
      source.connect(gain);
      gain.connect(low);
      low.connect(high);
      high.connect(inHead);
      high.connect(located);
      located.connect(panner);
      panner.connect(this.musicBus);
      inHead.connect(this.musicBus);
      inHead.gain.value = 1;
      located.gain.value = 0;
      source.start(this.t0);
      this.stems.set(spec.id, { source, gain, low, high, inHead, located, panner, trim, bearing: null, mode: "off", holdUntil: 0 });
    }
  }

  stopMusic() {
    for (const s of this.stems.values()) {
      try {
        s.source.stop();
      } catch {
        // never started
      }
      s.source.disconnect();
    }
    this.stems.clear();
  }

  /** HUSH's theme: a loop that rises as HUSH holds the frequency. */
  setTheme(buffer, gainDb) {
    if (this.theme) {
      this.theme.source.stop();
      this.theme.source.disconnect();
    }
    if (!buffer) return (this.theme = null);
    const source = this.ctx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    const gain = this.ctx.createGain();
    gain.gain.value = 0;
    source.connect(gain).connect(this.musicBus);
    source.start(this.now + 0.05);
    this.theme = { source, gain, base: DB(gainDb || 0) };
  }

  /** When does the next bar start? Collected layers come in on it, like in the app. */
  nextBar() {
    if (!this.barSeconds) return this.now;
    const into = (this.now - this.t0) % this.barSeconds;
    return this.now + (into < 0 ? -into : this.barSeconds - into);
  }

  // ------------------------------------------------------------------ the scene

  /** Applies the engine's AudioScene (see AudioSceneComposer). Called ~20 times a second. */
  setScene(scene) {
    const t = this.now;
    this.paused = scene.paused;
    const pausedFactor = scene.paused ? PAUSED_FACTOR : 1;
    for (const [id, s] of this.stems) {
      const st = scene.stems[id];
      const target = st ? st.gain * s.trim * pausedFactor : 0;
      const located = st && st.bearingDeg != null;
      const clarity = st ? st.clarity : 1;
      const mode = !st ? "off" : located ? "located" : "head";
      if (mode === "head" && s.mode !== "head") {
        // Into the head (captured): it comes in on the next bar, like in the app.
        const bar = this.nextBar();
        for (const [param, v] of [[s.gain.gain, target], [s.inHead.gain, 1], [s.located.gain, 0]]) {
          param.cancelScheduledValues(t);
          param.setTargetAtTime(v, bar, 0.04);
        }
        s.holdUntil = bar + 0.2;
      } else if (t >= s.holdUntil) {
        s.gain.gain.setTargetAtTime(target, t, 0.12);
        s.inHead.gain.setTargetAtTime(mode === "located" ? 0 : 1, t, 0.1);
        s.located.gain.setTargetAtTime(mode === "located" ? 1 : 0, t, 0.1);
      }
      s.mode = mode;
      s.bearing = located ? st.bearingDeg : null;
      if (located) this.aim(s.panner, st.bearingDeg, t);
      s.low.frequency.setTargetAtTime(500 + Math.pow(clarity, 2) * 17500, t, 0.15);
      s.high.frequency.setTargetAtTime(20 + (1 - clarity) * 520, t, 0.15);
    }
    if (this.theme) this.theme.gain.gain.setTargetAtTime(scene.antagonistLevel * this.theme.base * pausedFactor, t, 0.3);

    this.staticGain.gain.setTargetAtTime(scene.staticLevel * 0.22 * pausedFactor, t, 0.2);
    this.staticBearing = scene.staticBearingDeg;
    if (scene.staticBearingDeg != null) this.aim(this.staticPanner, scene.staticBearingDeg, t);

    if (scene.pingIntervalMs && !scene.paused) {
      if (t >= this.nextPingAt) {
        this.ping(scene.pingBearingDeg);
        this.nextPingAt = t + scene.pingIntervalMs / 1000;
      }
    } else {
      this.nextPingAt = t;
    }
    this.applyComms();
  }

  /** The runner's head (or phone) heading in degrees from north, or null when unknown. */
  setHeading(deg) {
    this.heading = deg;
    const t = this.now;
    for (const s of this.stems.values()) if (s.bearing != null) this.aim(s.panner, s.bearing, t);
    if (this.staticBearing != null) this.aim(this.staticPanner, this.staticBearing, t);
  }

  panner() {
    const p = this.ctx.createPanner();
    p.panningModel = "HRTF";
    p.distanceModel = "linear";
    p.refDistance = 1;
    p.maxDistance = 10000;
    p.rolloffFactor = 0; // the engine sets loudness; the panner only places
    p.positionZ.value = -1;
    return p;
  }

  /** Points a panner at a world bearing, relative to where the runner is facing. */
  aim(panner, worldBearingDeg, t = this.now) {
    const rel = ((worldBearingDeg - (this.heading ?? 0)) * Math.PI) / 180;
    panner.positionX.setTargetAtTime(Math.sin(rel), t, 0.03);
    panner.positionY.setTargetAtTime(0, t, 0.03);
    panner.positionZ.setTargetAtTime(-Math.cos(rel), t, 0.03);
  }

  // ------------------------------------------------------------------ static and ping

  makeStatic() {
    const ctx = this.ctx;
    const noise = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
    const d = noise.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    const src = ctx.createBufferSource();
    src.buffer = noise;
    src.loop = true;
    const band = ctx.createBiquadFilter();
    band.type = "bandpass";
    band.frequency.value = 1800;
    band.Q.value = 0.6;
    this.staticGain = ctx.createGain();
    this.staticGain.gain.value = 0;
    this.staticPanner = this.panner();
    src.connect(band).connect(this.staticGain).connect(this.staticPanner).connect(this.master);
    src.start();
    this.staticBearing = null;
  }

  ping(bearingDeg) {
    const t = this.now + 0.01;
    const osc = this.ctx.createOscillator();
    osc.frequency.value = 1320;
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.18, t + 0.008);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.18);
    const p = this.panner();
    if (bearingDeg != null) this.aim(p, bearingDeg, t);
    osc.connect(g).connect(p).connect(this.sfxBus);
    osc.start(t);
    osc.stop(t + 0.2);
  }

  // ------------------------------------------------------------------ comms ducking

  /** "voice" while a character speaks, "mic" while listening: the music steps back. */
  setComms(mode) {
    this.comms = mode;
    this.applyComms();
  }

  applyComms() {
    const duck = this.comms === "mic" ? 0.3 : this.comms === "voice" ? 0.5 : 1;
    this.musicBus.gain.setTargetAtTime(duck, this.now, 0.12);
  }

  // ------------------------------------------------------------------ voices

  /**
   * Plays one rendered line. [radio] 0 = clean, 1 = crushed walkie-talkie; a [bearingDeg] places
   * the voice at its relay in the park, otherwise it plays in the head. Resolves when it ends.
   */
  playVoice(buffer, { bearingDeg = null, radio = 0.4 } = {}) {
    const ctx = this.ctx;
    const t = this.now + 0.02;
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    const dry = ctx.createGain();
    dry.gain.value = 1 - radio * 0.75;
    const band = ctx.createBiquadFilter();
    band.type = "bandpass";
    band.frequency.value = 1400;
    band.Q.value = 0.9;
    const crush = ctx.createWaveShaper();
    crush.curve = drive(1 + radio * 18);
    const wet = ctx.createGain();
    wet.gain.value = radio * 0.9;
    const sum = ctx.createGain();
    src.connect(dry).connect(sum);
    src.connect(band).connect(crush).connect(wet).connect(sum);
    let out = sum;
    if (bearingDeg != null) {
      const p = this.panner();
      this.aim(p, bearingDeg, t);
      sum.connect(p);
      out = p;
    }
    out.connect(this.voiceBus);
    let done;
    const ended = new Promise((resolve) => (done = resolve));
    src.onended = () => done();
    src.start(t);
    return { ended, stop: () => { try { src.stop(); } catch { /* not started */ } done(); } };
  }

  // ------------------------------------------------------------------ effects

  sfx(name, bearingDeg = null) {
    const S = SFX[name];
    if (!S) return;
    const t = this.now + 0.01;
    let out = this.sfxBus;
    if (bearingDeg != null) {
      const p = this.panner();
      this.aim(p, bearingDeg, t);
      p.connect(this.sfxBus);
      out = p;
    }
    S(this.ctx, out, t);
  }

  /** The rhythm challenge: clicks on the track's beat grid, then [onDone]. */
  playRhythm(onsetsBeats, onDone) {
    const beat = this.beatSeconds || 0.5;
    const pos = (this.now - this.t0) / beat;
    let start = this.t0 + Math.ceil(pos) * beat;
    if (start - this.now < beat / 2) start += beat;
    for (const b of onsetsBeats) SFX.CLICK(this.ctx, this.sfxBus, start + b * beat);
    const end = start + Math.max(...onsetsBeats) * beat + beat / 2;
    setTimeout(onDone, Math.max(0, (end - this.now) * 1000));
  }

  /** A ping that circles the head once: left, behind, right, ahead. Proves the glasses are the output. */
  soundCheck() {
    const t0 = this.now + 0.05;
    [270, 180, 90, 0].forEach((b, i) => {
      const t = t0 + i * 0.45;
      const osc = this.ctx.createOscillator();
      osc.frequency.value = 880;
      const g = this.ctx.createGain();
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(0.25, t + 0.01);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
      const p = this.panner();
      const rel = (b * Math.PI) / 180;
      p.positionX.value = Math.sin(rel);
      p.positionZ.value = -Math.cos(rel);
      osc.connect(g).connect(p).connect(this.sfxBus);
      osc.start(t);
      osc.stop(t + 0.32);
    });
  }

  close() {
    this.stopMusic();
    this.setTheme(null);
    if (this.element) this.element.pause();
    this.ctx.close();
  }
}

function drive(k) {
  const n = 1024;
  const curve = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const x = (i / (n - 1)) * 2 - 1;
    curve[i] = Math.tanh(k * x) / Math.tanh(k);
  }
  return curve;
}

/** A short tone with an attack and an exponential tail. */
function tone(ctx, out, t, freq, dur, { type = "sine", level = 0.2, to = null } = {}) {
  const osc = ctx.createOscillator();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t);
  if (to) osc.frequency.exponentialRampToValueAtTime(to, t + dur);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(level, t + 0.008);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  osc.connect(g).connect(out);
  osc.start(t);
  osc.stop(t + dur + 0.02);
}

function noiseBurst(ctx, out, t, dur, freq, level) {
  const b = ctx.createBuffer(1, Math.ceil(ctx.sampleRate * dur), ctx.sampleRate);
  const d = b.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / d.length);
  const src = ctx.createBufferSource();
  src.buffer = b;
  const f = ctx.createBiquadFilter();
  f.type = "bandpass";
  f.frequency.value = freq;
  const g = ctx.createGain();
  g.gain.value = level;
  src.connect(f).connect(g).connect(out);
  src.start(t);
}

/** The same cues the app plays, synthesized. */
const SFX = {
  SIGNAL_ACQUIRED: (c, o, t) => { tone(c, o, t, 660, 0.14); tone(c, o, t + 0.12, 990, 0.22); },
  LOCK_AVAILABLE: (c, o, t) => [0, 0.11, 0.22].forEach((d) => tone(c, o, t + d, 1200, 0.08, { level: 0.16 })),
  LOCK: (c, o, t) => { tone(c, o, t, 110, 0.25, { type: "triangle", level: 0.35, to: 60 }); tone(c, o, t + 0.05, 1760, 0.18, { level: 0.12 }); },
  DENIED: (c, o, t) => tone(c, o, t, 180, 0.28, { type: "square", level: 0.08 }),
  COLLECT: (c, o, t) => [523.25, 659.25, 783.99, 1046.5].forEach((f, i) => tone(c, o, t + i * 0.07, f, 0.3, { level: 0.14 })),
  PAUSE: (c, o, t) => { tone(c, o, t, 880, 0.12); tone(c, o, t + 0.1, 587, 0.18); },
  RESUME: (c, o, t) => { tone(c, o, t, 587, 0.12); tone(c, o, t + 0.1, 880, 0.18); },
  RECORD_START: (c, o, t) => tone(c, o, t, 880, 0.2, { level: 0.18 }),
  TRANSMISSION: (c, o, t) => { noiseBurst(c, o, t, 0.25, 2200, 0.25); tone(c, o, t + 0.05, 1500, 0.2, { level: 0.1, to: 2400 }); },
  FINALE: (c, o, t) => [293.66, 369.99, 440, 587.33].forEach((f) => tone(c, o, t, f, 2.2, { type: "triangle", level: 0.08 })),
  MIC_OPEN: (c, o, t) => tone(c, o, t, 1500, 0.09, { level: 0.12, to: 2200 }),
  MIC_CLOSE: (c, o, t) => tone(c, o, t, 2200, 0.09, { level: 0.1, to: 1400 }),
  CLICK: (c, o, t) => tone(c, o, t, 2000, 0.04, { type: "square", level: 0.12 }),
};
