// Web Audio engine for the browser player: the counterpart of the app's
// binaural mixer. Positioned sounds pass through HRTF panners, so through
// the glasses (or any headphones) a signal sits where it is in the park
// and stays there as the runner turns.
//
//   stems -- in-head gain -------\
//          \- clarity filters -- HRTF -- music bus \
//   antagonist theme ------------- music bus        +-- master -- limiter -- out
//   static / ping / sfx -- HRTF -------------------/
//   voices -- radio chain -- (HRTF) -- voice bus --/
//
// Output is routed through an <audio> element when the browser allows it:
// that makes the page a media player, so the glasses' touchpad taps reach
// it through the Media Session API instead of pausing the music.

const dbToGain = (db) => Math.pow(10, db / 20);
const PAUSED_GAIN = 0.2;

export class AudioEngine {
  constructor() {
    const ContextClass = window.AudioContext || window.webkitAudioContext;
    this.context = new ContextClass({ latencyHint: "playback" });
    const ctx = this.context;

    this.master = ctx.createGain();
    this.limiter = ctx.createDynamicsCompressor();
    this.limiter.threshold.value = -6;
    this.limiter.ratio.value = 12;
    this.limiter.attack.value = 0.003;
    this.limiter.release.value = 0.2;
    this.master.connect(this.limiter);

    this.musicBus = this.makeBus(this.master);
    this.voiceBus = this.makeBus(this.master);
    this.sfxBus = this.makeBus(this.master);

    this.headingDeg = null;
    this.pitchDeg = null;
    this.stemNodes = new Map();
    this.theme = null;
    this.loopSeconds = 0;
    this.barSeconds = 0;
    this.beatSeconds = 0;
    this.musicStartAt = 0;
    this.commsMode = "none";
    this.isPaused = false;
    this.nextPingAt = 0;
    this.element = null;

    this.buildStaticSource();
    this.routeOutput();
  }

  makeBus(destination) {
    const gain = this.context.createGain();
    gain.connect(destination);
    return gain;
  }

  /** Routes through a media element when possible (see header note). */
  routeOutput() {
    const canUseElement = typeof this.context.createMediaStreamDestination === "function" &&
      "srcObject" in HTMLMediaElement.prototype;
    if (canUseElement) {
      this.streamDestination = this.context.createMediaStreamDestination();
      this.limiter.connect(this.streamDestination);
      this.element = new Audio();
      this.element.srcObject = this.streamDestination.stream;
      this.element.setAttribute("playsinline", "");
    } else {
      this.limiter.connect(this.context.destination);
    }
  }

  /** Call from a tap: browsers only allow sound after a user gesture. */
  async resume() {
    if (this.context.state !== "running") await this.context.resume();
    if (!this.element) return;
    try {
      await this.element.play();
    } catch {
      // The element refused: fall back to direct speaker output.
      this.limiter.disconnect();
      this.limiter.connect(this.context.destination);
      this.element = null;
    }
  }

  get now() {
    return this.context.currentTime;
  }

  setVolume(volume) {
    const clamped = Math.max(0, Math.min(1, volume));
    this.master.gain.setTargetAtTime(clamped, this.now, 0.05);
  }

  decode(arrayBuffer) {
    return this.context.decodeAudioData(arrayBuffer);
  }

  /** Conforms a buffer to exactly [frameCount]: trim or pad with silence. */
  conform(buffer, frameCount) {
    if (buffer.length === frameCount) return buffer;
    const out = this.context.createBuffer(buffer.numberOfChannels, frameCount, buffer.sampleRate);
    for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
      const source = buffer.getChannelData(channel);
      out.copyToChannel(source.subarray(0, Math.min(frameCount, buffer.length)), channel);
    }
    return out;
  }

  // -- music ---------------------------------------------------------------

  /** Starts every stem silent and sample-locked to one shared loop. */
  startMusic(music, buffers) {
    this.stopMusic();
    const sampleRate = this.context.sampleRate;
    this.beatSeconds = 60 / music.bpm;
    this.barSeconds = this.beatSeconds * (music.beatsPerBar || 4);
    this.loopSeconds = this.barSeconds * (music.loopBars || 8);
    const loopFrames = Math.round(this.loopSeconds * sampleRate);
    this.musicStartAt = this.now + 0.15;

    for (const stemSpec of music.stems) {
      const buffer = buffers.get(stemSpec.id);
      if (!buffer) continue;
      const nodes = this.buildStemChain(stemSpec, buffer, loopFrames);
      nodes.source.start(this.musicStartAt);
      this.stemNodes.set(stemSpec.id, nodes);
    }
  }

  buildStemChain(stemSpec, buffer, loopFrames) {
    const ctx = this.context;
    const source = ctx.createBufferSource();
    source.buffer = this.conform(buffer, loopFrames);
    source.loop = true;

    const gain = ctx.createGain();
    gain.gain.value = 0;

    // Distance is heard as dullness: far stems are muffled and thin.
    const lowpass = ctx.createBiquadFilter();
    lowpass.type = "lowpass";
    lowpass.frequency.value = 600;
    const highpass = ctx.createBiquadFilter();
    highpass.type = "highpass";
    highpass.frequency.value = 20;

    const inHead = ctx.createGain();
    inHead.gain.value = 1;
    const located = ctx.createGain();
    located.gain.value = 0;
    const panner = this.makePanner();

    source.connect(gain);
    gain.connect(lowpass);
    lowpass.connect(highpass);
    highpass.connect(inHead);
    highpass.connect(located);
    located.connect(panner);
    panner.connect(this.musicBus);
    inHead.connect(this.musicBus);

    return {
      source, gain, lowpass, highpass, inHead, located, panner,
      trim: dbToGain(stemSpec.gainDb || 0),
      bearingDeg: null,
      elevationDeg: 0,
      mode: "off",
      holdUntil: 0,
    };
  }

  stopMusic() {
    for (const nodes of this.stemNodes.values()) {
      try { nodes.source.stop(); } catch { /* never started */ }
      nodes.source.disconnect();
    }
    this.stemNodes.clear();
  }

  /** The antagonist theme loop; its gain follows antagonistLevel. */
  setTheme(buffer, gainDb) {
    if (this.theme) {
      this.theme.source.stop();
      this.theme.source.disconnect();
      this.theme = null;
    }
    if (!buffer) return;
    const source = this.context.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    const gain = this.context.createGain();
    gain.gain.value = 0;
    source.connect(gain).connect(this.musicBus);
    source.start(this.now + 0.05);
    this.theme = { source, gain, baseGain: dbToGain(gainDb || 0) };
  }

  /** Start time of the next bar; captured stems enter on it. */
  nextBarStart() {
    if (!this.barSeconds) return this.now;
    const intoBar = (this.now - this.musicStartAt) % this.barSeconds;
    return this.now + (intoBar < 0 ? -intoBar : this.barSeconds - intoBar);
  }

  // -- scene -----------------------------------------------------------------

  /** Applies one AudioScene from the engine (called ~20x/second). */
  setScene(scene) {
    const at = this.now;
    this.isPaused = scene.paused;
    const pauseFactor = scene.paused ? PAUSED_GAIN : 1;

    for (const [stemId, nodes] of this.stemNodes) {
      const state = scene.stems[stemId];
      const targetGain = state ? state.gain * nodes.trim * pauseFactor : 0;
      const isLocated = Boolean(state && state.bearingDeg != null);
      const clarity = state ? state.clarity : 1;
      const mode = !state ? "off" : isLocated ? "located" : "head";

      if (mode === "head" && nodes.mode !== "head") {
        // Just captured: the stem joins the head mix on the next bar.
        const barAt = this.nextBarStart();
        const arrivals = [
          [nodes.gain.gain, targetGain],
          [nodes.inHead.gain, 1],
          [nodes.located.gain, 0],
        ];
        for (const [param, value] of arrivals) {
          param.cancelScheduledValues(at);
          param.setTargetAtTime(value, barAt, 0.04);
        }
        nodes.holdUntil = barAt + 0.2;
      } else if (at >= nodes.holdUntil) {
        nodes.gain.gain.setTargetAtTime(targetGain, at, 0.12);
        nodes.inHead.gain.setTargetAtTime(mode === "located" ? 0 : 1, at, 0.1);
        nodes.located.gain.setTargetAtTime(mode === "located" ? 1 : 0, at, 0.1);
      }

      nodes.mode = mode;
      nodes.bearingDeg = isLocated ? state.bearingDeg : null;
      nodes.elevationDeg = state?.elevationDeg ?? 0;
      if (isLocated) this.aimPanner(nodes.panner, state.bearingDeg, at, state.elevationDeg ?? 0);
      nodes.lowpass.frequency.setTargetAtTime(500 + Math.pow(clarity, 2) * 17500, at, 0.15);
      nodes.highpass.frequency.setTargetAtTime(20 + (1 - clarity) * 520, at, 0.15);
    }

    if (this.theme) {
      const themeGain = scene.antagonistLevel * this.theme.baseGain * pauseFactor;
      this.theme.gain.gain.setTargetAtTime(themeGain, at, 0.3);
    }

    this.staticGain.gain.setTargetAtTime(scene.staticLevel * 0.22 * pauseFactor, at, 0.2);
    this.staticBearingDeg = scene.staticBearingDeg;
    this.staticElevationDeg = scene.staticElevationDeg ?? 0;
    if (scene.staticBearingDeg != null) {
      this.aimPanner(this.staticPanner, scene.staticBearingDeg, at, scene.staticElevationDeg ?? 0);
    }

    if (scene.pingIntervalMs && !scene.paused) {
      if (at >= this.nextPingAt) {
        this.playPing(scene.pingBearingDeg, scene.pingElevationDeg ?? 0);
        this.nextPingAt = at + scene.pingIntervalMs / 1000;
      }
    } else {
      this.nextPingAt = at;
    }

    this.applyCommsDuck();
  }

  /** Head/phone heading (and pitch) in degrees; null when unknown. */
  setHeading(headingDeg, pitchDeg = null) {
    this.headingDeg = headingDeg;
    this.pitchDeg = pitchDeg;
    const at = this.now;
    for (const nodes of this.stemNodes.values()) {
      if (nodes.bearingDeg != null) {
        this.aimPanner(nodes.panner, nodes.bearingDeg, at, nodes.elevationDeg ?? 0);
      }
    }
    if (this.staticBearingDeg != null) {
      this.aimPanner(this.staticPanner, this.staticBearingDeg, at, this.staticElevationDeg ?? 0);
    }
  }

  makePanner() {
    const panner = this.context.createPanner();
    panner.panningModel = "HRTF";
    panner.distanceModel = "linear";
    panner.refDistance = 1;
    panner.maxDistance = 10000;
    panner.rolloffFactor = 0; // Loudness comes from the engine; this only places.
    panner.positionZ.value = -1;
    return panner;
  }

  /** Points a panner at a world bearing relative to the runner's facing. */
  aimPanner(panner, worldBearingDeg, at = this.now, elevationDeg = 0) {
    const relativeAzimuth = ((worldBearingDeg - (this.headingDeg ?? 0)) * Math.PI) / 180;
    const relativeElevation = ((elevationDeg - (this.pitchDeg ?? 0)) * Math.PI) / 180;
    panner.positionX.setTargetAtTime(Math.sin(relativeAzimuth) * Math.cos(relativeElevation), at, 0.03);
    panner.positionY.setTargetAtTime(Math.sin(relativeElevation), at, 0.03);
    panner.positionZ.setTargetAtTime(-Math.cos(relativeAzimuth) * Math.cos(relativeElevation), at, 0.03);
  }

  // -- static and ping ----------------------------------------------------------

  buildStaticSource() {
    const ctx = this.context;
    const noiseBuffer = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
    const samples = noiseBuffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) samples[i] = Math.random() * 2 - 1;

    const source = ctx.createBufferSource();
    source.buffer = noiseBuffer;
    source.loop = true;
    const bandpass = ctx.createBiquadFilter();
    bandpass.type = "bandpass";
    bandpass.frequency.value = 1800;
    bandpass.Q.value = 0.6;

    this.staticGain = ctx.createGain();
    this.staticGain.gain.value = 0;
    this.staticPanner = this.makePanner();
    this.staticBearingDeg = null;
    this.staticElevationDeg = 0;

    source.connect(bandpass).connect(this.staticGain).connect(this.staticPanner).connect(this.master);
    source.start();
  }

  playPing(bearingDeg, elevationDeg = 0) {
    const at = this.now + 0.01;
    const oscillator = this.context.createOscillator();
    oscillator.frequency.value = 1320;
    const envelope = this.context.createGain();
    envelope.gain.setValueAtTime(0.0001, at);
    envelope.gain.exponentialRampToValueAtTime(0.18, at + 0.008);
    envelope.gain.exponentialRampToValueAtTime(0.0001, at + 0.18);
    const panner = this.makePanner();
    if (bearingDeg != null) this.aimPanner(panner, bearingDeg, at, elevationDeg);
    oscillator.connect(envelope).connect(panner).connect(this.sfxBus);
    oscillator.start(at);
    oscillator.stop(at + 0.2);
  }

  // -- comms ducking --------------------------------------------------------------

  /** "voice" while a character speaks, "mic" while listening. */
  setComms(mode) {
    this.commsMode = mode;
    this.applyCommsDuck();
  }

  applyCommsDuck() {
    const duck = this.commsMode === "mic" ? 0.3 : this.commsMode === "voice" ? 0.5 : 1;
    this.musicBus.gain.setTargetAtTime(duck, this.now, 0.12);
  }

  // -- voices -----------------------------------------------------------------------

  /**
   * Plays one rendered voice line. [radio] runs 0 (clean) to 1 (crushed
   * walkie-talkie). A bearing places the voice at its relay; otherwise it
   * plays in the head. Returns { ended, stop }.
   */
  playVoice(buffer, { bearingDeg = null, radio = 0.4 } = {}) {
    const ctx = this.context;
    const startAt = this.now + 0.02;

    const source = ctx.createBufferSource();
    source.buffer = buffer;

    const dry = ctx.createGain();
    dry.gain.value = 1 - radio * 0.75;

    const bandpass = ctx.createBiquadFilter();
    bandpass.type = "bandpass";
    bandpass.frequency.value = 1400;
    bandpass.Q.value = 0.9;

    const waveshaper = ctx.createWaveShaper();
    waveshaper.curve = driveCurve(1 + radio * 18);

    const wet = ctx.createGain();
    wet.gain.value = radio * 0.9;

    const mix = ctx.createGain();
    source.connect(dry).connect(mix);
    source.connect(bandpass).connect(waveshaper).connect(wet).connect(mix);

    let output = mix;
    if (bearingDeg != null) {
      const panner = this.makePanner();
      this.aimPanner(panner, bearingDeg, startAt);
      mix.connect(panner);
      output = panner;
    }
    output.connect(this.voiceBus);

    let resolveEnded;
    const ended = new Promise((resolve) => { resolveEnded = resolve; });
    source.onended = () => resolveEnded();
    source.start(startAt);

    return {
      ended,
      stop: () => {
        try { source.stop(); } catch { /* not started yet */ }
        resolveEnded();
      },
    };
  }

  // -- effects ------------------------------------------------------------------------

  sfx(name, bearingDeg = null) {
    const cue = SFX_CUES[name];
    if (!cue) return;
    const at = this.now + 0.01;
    let output = this.sfxBus;
    if (bearingDeg != null) {
      const panner = this.makePanner();
      this.aimPanner(panner, bearingDeg, at);
      panner.connect(this.sfxBus);
      output = panner;
    }
    cue(this.context, output, at);
  }

  /** Rhythm challenge: clicks on the track's beat grid, then onDone. */
  playRhythm(onsetsBeats, onDone) {
    const beatSeconds = this.beatSeconds || 0.5;
    const positionBeats = (this.now - this.musicStartAt) / beatSeconds;
    let gridStart = this.musicStartAt + Math.ceil(positionBeats) * beatSeconds;
    if (gridStart - this.now < beatSeconds / 2) gridStart += beatSeconds;
    for (const onset of onsetsBeats) {
      SFX_CUES.CLICK(this.context, this.sfxBus, gridStart + onset * beatSeconds);
    }
    const endAt = gridStart + Math.max(...onsetsBeats) * beatSeconds + beatSeconds / 2;
    setTimeout(onDone, Math.max(0, (endAt - this.now) * 1000));
  }

  /** A ping circling the head once: left, behind, right, ahead. */
  soundCheck() {
    const startAt = this.now + 0.05;
    [270, 180, 90, 0].forEach((bearing, index) => {
      const at = startAt + index * 0.45;
      const oscillator = this.context.createOscillator();
      oscillator.frequency.value = 880;
      const envelope = this.context.createGain();
      envelope.gain.setValueAtTime(0.0001, at);
      envelope.gain.exponentialRampToValueAtTime(0.25, at + 0.01);
      envelope.gain.exponentialRampToValueAtTime(0.0001, at + 0.3);
      const panner = this.makePanner();
      const radians = (bearing * Math.PI) / 180;
      panner.positionX.value = Math.sin(radians);
      panner.positionZ.value = -Math.cos(radians);
      oscillator.connect(envelope).connect(panner).connect(this.sfxBus);
      oscillator.start(at);
      oscillator.stop(at + 0.32);
    });
  }

  close() {
    this.stopMusic();
    this.setTheme(null);
    if (this.element) this.element.pause();
    this.context.close();
  }
}

/** Soft-clipping drive curve for the radio voice chain. */
function driveCurve(amount) {
  const length = 1024;
  const curve = new Float32Array(length);
  for (let i = 0; i < length; i++) {
    const x = (i / (length - 1)) * 2 - 1;
    curve[i] = Math.tanh(amount * x) / Math.tanh(amount);
  }
  return curve;
}

/** One short tone with a fast attack and an exponential tail. */
function playTone(ctx, output, at, frequency, durationS, { type = "sine", level = 0.2, glideTo = null } = {}) {
  const oscillator = ctx.createOscillator();
  oscillator.type = type;
  oscillator.frequency.setValueAtTime(frequency, at);
  if (glideTo) oscillator.frequency.exponentialRampToValueAtTime(glideTo, at + durationS);
  const envelope = ctx.createGain();
  envelope.gain.setValueAtTime(0.0001, at);
  envelope.gain.exponentialRampToValueAtTime(level, at + 0.008);
  envelope.gain.exponentialRampToValueAtTime(0.0001, at + durationS);
  oscillator.connect(envelope).connect(output);
  oscillator.start(at);
  oscillator.stop(at + durationS + 0.02);
}

function playNoiseBurst(ctx, output, at, durationS, frequency, level) {
  const buffer = ctx.createBuffer(1, Math.ceil(ctx.sampleRate * durationS), ctx.sampleRate);
  const samples = buffer.getChannelData(0);
  for (let i = 0; i < samples.length; i++) {
    samples[i] = (Math.random() * 2 - 1) * (1 - i / samples.length);
  }
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  const bandpass = ctx.createBiquadFilter();
  bandpass.type = "bandpass";
  bandpass.frequency.value = frequency;
  const gain = ctx.createGain();
  gain.gain.value = level;
  source.connect(bandpass).connect(gain).connect(output);
  source.start(at);
}

// Synthesized cues, matching the app's set.
const SFX_CUES = {
  SIGNAL_ACQUIRED: (ctx, out, at) => {
    playTone(ctx, out, at, 660, 0.14);
    playTone(ctx, out, at + 0.12, 990, 0.22);
  },
  LOCK_AVAILABLE: (ctx, out, at) =>
    [0, 0.11, 0.22].forEach((delay) => playTone(ctx, out, at + delay, 1200, 0.08, { level: 0.16 })),
  LOCK: (ctx, out, at) => {
    playTone(ctx, out, at, 110, 0.25, { type: "triangle", level: 0.35, glideTo: 60 });
    playTone(ctx, out, at + 0.05, 1760, 0.18, { level: 0.12 });
  },
  DENIED: (ctx, out, at) => playTone(ctx, out, at, 180, 0.28, { type: "square", level: 0.08 }),
  COLLECT: (ctx, out, at) =>
    [523.25, 659.25, 783.99, 1046.5].forEach((freq, i) => playTone(ctx, out, at + i * 0.07, freq, 0.3, { level: 0.14 })),
  PAUSE: (ctx, out, at) => {
    playTone(ctx, out, at, 880, 0.12);
    playTone(ctx, out, at + 0.1, 587, 0.18);
  },
  RESUME: (ctx, out, at) => {
    playTone(ctx, out, at, 587, 0.12);
    playTone(ctx, out, at + 0.1, 880, 0.18);
  },
  RECORD_START: (ctx, out, at) => playTone(ctx, out, at, 880, 0.2, { level: 0.18 }),
  TRANSMISSION: (ctx, out, at) => {
    playNoiseBurst(ctx, out, at, 0.25, 2200, 0.25);
    playTone(ctx, out, at + 0.05, 1500, 0.2, { level: 0.1, glideTo: 2400 });
  },
  FINALE: (ctx, out, at) =>
    [293.66, 369.99, 440, 587.33].forEach((freq) => playTone(ctx, out, at, freq, 2.2, { type: "triangle", level: 0.08 })),
  MIC_OPEN: (ctx, out, at) => playTone(ctx, out, at, 1500, 0.09, { level: 0.12, glideTo: 2200 }),
  MIC_CLOSE: (ctx, out, at) => playTone(ctx, out, at, 2200, 0.09, { level: 0.1, glideTo: 1400 }),
  CLICK: (ctx, out, at) => playTone(ctx, out, at, 2000, 0.04, { type: "square", level: 0.12 }),
};
