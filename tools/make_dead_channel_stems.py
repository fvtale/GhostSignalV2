#!/usr/bin/env python3
"""Synthesises "Dead Channel", the soundtrack a runner unlocks by finishing the mission once.

Vesper's night mix of the Last Eight Bars: slower and colder than the original, in the spirit of
analog-synth game scores. Detuned pads, a warbly climbing arpeggio, crushed ticks, a pulsing
undertow and a ghost choir, all on tape that wanders a little.

It uses the same eight slots as the original (kick, hats, bass, perc, pad, arp, lead, vox), so
every signal still unlocks the part the script talks about. 07_lead is the violin slot: what is
here is a bowed-synth stand-in in violin range, and the mix leaves the 600 Hz - 3 kHz band clear
for a real violin take. Record over it at 88 BPM in D minor and replace the file (see
docs/AUDIO.md).

Each stem is 8 bars at 88 BPM (about 21.8 s), the same length to the sample, and everything that
rings (reverb, delay, tape wobble) wraps around the loop point, so the files loop without a seam.

Usage: python3 tools/make_dead_channel_stems.py [out_dir]   (needs numpy and ffmpeg)
"""

import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 48_000
BPM = 88.0
BEAT = 60.0 / BPM
BARS = 8
N = int(round(BARS * 4 * BEAT * SR))
rng = np.random.default_rng(88)

# D minor, two bars per chord: Dm9 | Bbmaj9 | Gm9 | A7sus4 -> A7.
# (bass root, pad voicing, choir voicing) per two bars; the last chord resolves halfway.
CHORDS = [
    (38, [50, 57, 60, 64, 65], [62, 65, 69, 72]),
    (34, [46, 53, 57, 60, 62], [62, 65, 69, 72]),
    (31, [50, 55, 58, 62, 69], [62, 67, 70, 74]),
    (33, [52, 57, 62, 67], [64, 69, 74, 76]),
]
A7 = (33, [52, 57, 61, 67], [64, 69, 73, 76])


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def chord_at(beat):
    i = int(beat // 8) % 4
    if i == 3 and beat % 8 >= 4:
        return A7
    return CHORDS[i]


def t_of(n):
    return np.arange(n) / SR


# ---------------------------------------------------------------- loop-aware building blocks


def buf():
    return np.zeros((N, 2))


def add(out, start_s, sig, pan=0.0):
    """Mixes a mono or stereo signal into `out` at `start_s`, wrapping around the loop end."""
    i = int(round(start_s * SR)) % N
    if sig.ndim == 1:
        left, right = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
        sig = np.stack([sig * left, sig * right], axis=1)
    n = len(sig)
    first = min(n, N - i)
    out[i : i + first] += sig[:first]
    if n > first:
        rest = sig[first:]
        while len(rest):
            k = min(len(rest), N)
            out[:k] += rest[:k]
            rest = rest[k:]


def spectral(x, response):
    """Static filter applied in the frequency domain. Circular, so it never puts a seam in a loop."""
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    H = response(np.maximum(f, 1e-3))
    return np.fft.irfft(X * (H[:, None] if x.ndim > 1 else H), n=len(x), axis=0)


def lowpass(x, fc, order=2):
    return spectral(x, lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order)))


def highpass(x, fc, order=2):
    return spectral(x, lambda f: 1 / np.sqrt(1 + (fc / f) ** (2 * order)))


def peak_eq(x, fc, gain_db, width_oct):
    """Bell boost or cut on a log-frequency axis."""
    g = 10 ** (gain_db / 20) - 1
    return spectral(x, lambda f: 1 + g * np.exp(-0.5 * (np.log2(f / fc) / (width_oct / 2.355)) ** 2))


def formants(x, peaks):
    """Sum of resonant bells: (freq, gain, width in octaves). Used for the choir's vowel and the violin body."""

    def h(f):
        r = np.full_like(f, 0.04)
        for fc, gain, w in peaks:
            r += gain * np.exp(-0.5 * (np.log2(f / fc) / (w / 2.355)) ** 2)
        return r

    return spectral(x, h)


def reverb(x, decay_s, wet, damp_hz=5000.0, predelay_s=0.02, seed=0):
    """Convolution with a decorrelated stereo noise tail. Circular, so the tail wraps onto the loop start."""
    r = np.random.default_rng(seed)
    n = min(int(decay_s * 1.6 * SR), N - 1)
    t = t_of(n)
    ir = r.normal(0, 1, (n, 2)) * np.exp(-6.9 * t / decay_s)[:, None]  # -60 dB at decay_s
    ir[: int(predelay_s * SR)] = 0
    ir = lowpass(ir, damp_hz, order=1)
    ir /= np.sqrt((ir ** 2).sum(axis=0, keepdims=True)) + 1e-12
    full = np.zeros((N, 2))
    full[:n] = ir
    src = x if x.ndim > 1 else np.stack([x, x], axis=1)
    tail = np.fft.irfft(np.fft.rfft(src, axis=0) * np.fft.rfft(full, axis=0), n=N, axis=0)
    return src * (1 - wet) + tail * wet * 1.4


def wobble(x, depth_ms, cycles, phase=0.0, flutter_ms=0.0):
    """Tape wow: a variable delay whose modulation completes whole cycles per loop, so it stays seamless."""
    n = np.arange(N, dtype=float)
    d = depth_ms / 1000 * SR * (1 + np.sin(2 * np.pi * cycles * n / N + phase))
    if flutter_ms:
        d += flutter_ms / 1000 * SR * (1 + np.sin(2 * np.pi * cycles * 11 * n / N + 1.3))
    pos = (n - d) % N
    i0 = np.floor(pos).astype(int)
    frac = pos - i0
    i1 = (i0 + 1) % N
    if x.ndim == 1:
        return x[i0] * (1 - frac) + x[i1] * frac
    return x[i0] * (1 - frac)[:, None] + x[i1] * frac[:, None]


def delay(x, time_s, feedback, wet, pingpong=True, taps=6):
    """Circular feedback delay, ping-ponging between the channels."""
    out = x.copy()
    d = int(round(time_s * SR))
    src = x if x.ndim > 1 else np.stack([x, x], axis=1)
    for k in range(1, taps + 1):
        echo = np.roll(src, d * k, axis=0) * (feedback ** (k - 1)) * wet
        if pingpong and k % 2:
            echo = echo[:, ::-1]
        out = out + echo
    return out


def crush(x, hold, levels):
    """Sample-and-hold plus amplitude quantisation: the 8-bit grit."""
    idx = (np.arange(len(x)) // hold) * hold
    y = x[idx]
    return np.round(y * levels) / levels


def saturate(x, drive):
    return np.tanh(x * drive) / np.tanh(drive)


def env_adsr(n, a, d, s, r_s, hold_s):
    """Attack / decay / sustain over `hold_s`, then release. Lengths in seconds; returns n samples."""
    t = t_of(n)
    e = np.where(t < a, t / max(a, 1e-4), s + (1 - s) * np.exp(-(t - a) / max(d, 1e-4)))
    rel = np.clip((t - hold_s) / max(r_s, 1e-4), 0, 1)
    return e * (1 - rel) * (t < hold_s + r_s)


def additive_saw(freq_t, max_hz=11_000.0, brightness=1.0):
    """Band-limited saw from a per-sample frequency curve."""
    phase = 2 * np.pi * np.cumsum(freq_t) / SR
    top = int(max_hz / max(freq_t.max(), 1))
    out = np.zeros_like(phase)
    for k in range(1, max(top, 1) + 1):
        out += np.sin(k * phase) / (k ** brightness)
    return out * 0.6


# ---------------------------------------------------------------- the stems


def kick():
    """The Heartbeat: a soft analog lub-dub on beats one and three."""
    out = buf()
    for beat in np.arange(0, BARS * 4, 2.0):
        for off, vel in ((0.0, 1.0), (0.36, 0.58)):
            n = int(0.55 * SR)
            t = t_of(n)
            pitch = 40 + 85 * np.exp(-t * 28)
            body = np.sin(2 * np.pi * np.cumsum(pitch) / SR) * np.exp(-t * 7.5)
            # Open-ear speakers barely play below 150 Hz, so the beat also needs a knock above it.
            knock = np.sin(2 * np.pi * np.cumsum(170 + 90 * np.exp(-t * 40)) / SR) * np.exp(-t * 26) * 0.55
            click = lowpass(rng.normal(0, 1, n) * np.exp(-t * 400), 2500, 1) * 0.12
            add(out, (beat + off) * BEAT, saturate(body + knock + click, 1.8) * vel)
    out = reverb(out, 0.9, 0.12, damp_hz=1200, seed=1)
    return out


def hats():
    """The Stacks: brittle, crushed ticks with a little swing, over tape dust."""
    out = buf()
    for step in range(BARS * 16):
        swing = 0.035 * BEAT if step % 2 else 0.0
        pos = step * BEAT / 4 + swing
        accent = 1.0 if step % 4 == 2 else 0.55 if step % 2 == 0 else 0.32
        if step % 16 == 14:
            accent = 0.9
        n = int((0.12 if step % 16 == 14 else 0.05) * SR)
        t = t_of(n)
        noise = rng.normal(0, 1, n) * np.exp(-t * (18 if step % 16 == 14 else 75))
        glass = sum(np.sin(2 * np.pi * f * t) for f in (3150, 4730, 6290)) * np.exp(-t * 60) * 0.25
        add(out, pos, (noise + glass) * accent, pan=(-0.35 if step % 4 == 1 else 0.3))
    out = highpass(out, 5200, 2)
    out = np.stack([crush(out[:, c], 4, 12) for c in (0, 1)], axis=1)
    dust = (rng.random((N, 2)) < 0.00025) * rng.normal(0, 1.6, (N, 2))
    out += lowpass(highpass(dust, 1500), 7000) * 0.5
    return reverb(out, 1.2, 0.18, damp_hz=8000, seed=2)


def bass():
    """The Undertow: a sub that holds each root, under a pulsing filtered saw in eighths."""
    out = buf()
    for i in range(4):
        root = CHORDS[i][0]
        start = i * 8 * BEAT
        n = int(8 * BEAT * SR) + int(0.3 * SR)
        t = t_of(n)
        sub = np.sin(2 * np.pi * hz(root + 12) * t) * env_adsr(n, 0.05, 1.0, 0.9, 0.3, 8 * BEAT)
        add(out, start, sub * 0.28)
    for eighth in range(BARS * 8):
        beat = eighth / 2
        root = chord_at(beat)[0] + 24
        n = int(BEAT / 2 * SR)
        t = t_of(n)
        f = np.full(n, hz(root))
        tone = additive_saw(f, max_hz=3000)
        e = np.exp(-t * 9) * np.minimum(t / 0.004, 1)
        vel = 1.0 if eighth % 2 == 0 else 0.7
        add(out, beat * BEAT, tone * e * vel * 0.9)
    out = lowpass(out, 1200, 2)
    out = saturate(out * 1.4, 1.8)
    return np.stack([out.mean(axis=1)] * 2, axis=1)


def perc():
    """Bucket Line: Mari's motif (boom, ka, boom boom, ka) on low toms and a rim, far down the street."""
    out = buf()
    motif = ((0.0, "boom"), (1.0, "ka"), (1.5, "boom"), (2.0, "boom"), (3.0, "ka"))
    for bar in range(BARS):
        vel = 1.0 if bar % 2 == 0 else 0.6
        for onset, kind in motif:
            pos = (bar * 4 + onset) * BEAT
            if kind == "boom":
                n = int(0.45 * SR)
                t = t_of(n)
                pitch = 150 + 90 * np.exp(-t * 22)
                ph = 2 * np.pi * np.cumsum(pitch) / SR
                hit = (np.sin(ph) + 0.45 * np.sin(1.59 * ph)) * np.exp(-t * 9)
                hit += lowpass(rng.normal(0, 1, n), 900, 1) * np.exp(-t * 40) * 0.25
                add(out, pos, hit * vel, pan=-0.2)
            else:
                n = int(0.2 * SR)
                t = t_of(n)
                rim = np.sin(2 * np.pi * 1180 * t) * np.exp(-t * 55) + rng.normal(0, 1, n) * np.exp(-t * 90) * 0.6
                add(out, pos, rim * vel * 0.6, pan=0.25)
        if bar % 2 == 1:  # a ghost note answering the motif
            n = int(0.3 * SR)
            t = t_of(n)
            add(out, (bar * 4 + 3.5) * BEAT, np.sin(2 * np.pi * 210 * t) * np.exp(-t * 14) * 0.35, pan=0.4)
    out = peak_eq(out, 300, 3, 1.2)
    return reverb(out, 1.8, 0.32, damp_hz=3500, seed=4)


def pad():
    """The Fog: a wide detuned pad, breathing through its filter, on wandering tape."""
    out = buf()
    detunes = {0: (-14, -6, 0, 5, 11), 1: (-11, -4, 2, 7, 15)}
    for i in range(4):
        _, voicing, _ = CHORDS[i]
        start = i * 8 * BEAT
        length = 8 * BEAT
        n = int((length + 1.6) * SR)
        t = t_of(n)
        e = env_adsr(n, 1.1, 2.0, 0.85, 1.6, length)
        # The last chord is A7sus4 resolving to A7 halfway: the D falls to C#, with a short crossfade.
        fall = np.clip((t - length / 2) / 0.12, 0, 1)
        sig = np.zeros((n, 2))
        for ch in (0, 1):
            for m in list(voicing) + ([61] if i == 3 else []):
                gate = 1.0
                if i == 3 and m == 62:
                    gate = 1 - fall
                elif m == 61:
                    gate = fall
                for c in detunes[ch]:
                    f = hz(m) * 2 ** (c / 1200)
                    sig[:, ch] += (2 * ((t * f + rng.random()) % 1.0) - 1) * gate
        add(out, start, sig * e[:, None] / 12)
    soft = lowpass(out, 900, 2)
    open_ = lowpass(out, 2300, 2)
    breathe = 0.5 - 0.5 * np.cos(2 * np.pi * 2 * np.arange(N) / N)  # two slow breaths per loop
    out = soft * (1 - breathe[:, None]) + open_ * breathe[:, None]
    out = highpass(peak_eq(out, 1300, -4.5, 2.2), 110, 2)  # room for the violin, and for the bass
    out = wobble(out, 0.55, 13, flutter_ms=0.05)
    return reverb(out, 3.8, 0.38, damp_hz=4200, seed=5)


def arp():
    """The Spire: a pulse-wave arpeggio that climbs two octaves a bar, warbling on old tape."""
    out = buf()
    for step in range(BARS * 16):
        beat = step / 4
        _, voicing, _ = chord_at(beat)
        tones = sorted(set(m % 12 for m in voicing))
        ladder = [60 + p for p in tones] + [72 + p for p in tones] + [84 + tones[0]]
        idx = step % 16
        note = ladder[min(idx * len(ladder) // 16, len(ladder) - 1)]
        if idx >= 14:
            note = ladder[-1 - (idx - 14)]
        n = int(0.22 * SR)
        t = t_of(n)
        f = hz(note)
        duty = 0.25 + 0.1 * np.sin(2 * np.pi * step / 32)
        pulse = np.where((t * f) % 1.0 < duty, 1.0, -1.0) - (2 * duty - 1)
        e = np.exp(-t * 16) * np.minimum(t / 0.002, 1)
        add(out, beat * BEAT, pulse * e * (0.9 if idx % 4 == 0 else 0.65), pan=0.45 if step % 2 else -0.45)
    out = highpass(lowpass(out, 3400, 2), 180, 2)
    out = np.stack([crush(out[:, c], 2, 48) for c in (0, 1)], axis=1)
    out = delay(out, BEAT * 0.75, 0.45, 0.32)
    out = wobble(out, 1.6, 13, phase=0.4, flutter_ms=0.12)
    return reverb(out, 2.2, 0.25, damp_hz=5000, seed=6)


# The violin stand-in: (midi, beats), 32 beats. Lyrical, mostly stepwise, A4 to G5.
VIOLIN = [
    (69, 2), (72, 1), (74, 1), (76, 3), (74, 1),
    (77, 2), (76, 1), (74, 1), (72, 2), (69, 2),
    (70, 2), (74, 2), (79, 3), (77, 1),
    (76, 2), (74, 1), (73, 1), (69, 3), (None, 1),
]


def lead():
    """The Voice: the violin slot. A bowed-synth stand-in until the real take replaces it."""
    out = buf()
    beat = 0.0
    for note, dur in VIOLIN:
        if note is not None:
            length = dur * BEAT
            n = int((length + 0.35) * SR)
            t = t_of(n)
            vib = 1 + 0.0105 * np.sin(2 * np.pi * 5.6 * t) * np.clip((t - 0.25) / 0.4, 0, 1)
            f = hz(note) * vib
            tone = additive_saw(f, max_hz=9000, brightness=1.1)
            bow = lowpass(highpass(rng.normal(0, 1, n), 2500), 7000) * 0.05
            e = env_adsr(n, 0.12, 0.6, 0.85, 0.25, length)
            swell = 1 + 0.15 * np.sin(np.pi * np.clip(t / max(length, 0.1), 0, 1))
            add(out, beat * BEAT, (tone + bow) * e * swell, pan=0.05)
        beat += dur
    body = [(280, 1.0, 0.8), (480, 1.4, 0.7), (1150, 0.9, 0.9), (2800, 1.2, 0.8), (4300, 0.5, 1.0)]
    out = formants(out, body)
    out = lowpass(out, 6500, 2)
    return reverb(out, 2.4, 0.26, damp_hz=5200, seed=7)


def vox():
    """The Last Eight Bars: a ghost choir on "ah", with reversed swells into every chord."""
    out = buf()
    for i in range(4):
        _, _, voicing = CHORDS[i]
        start = i * 8 * BEAT
        length = 8 * BEAT
        n = int((length + 2.0) * SR)
        t = t_of(n)
        e = env_adsr(n, 1.8, 2.5, 0.8, 2.0, length)
        for m in voicing:
            for k in range(3):  # three singers per note
                cents = rng.normal(0, 7)
                rate = 4.6 + rng.random() * 1.2
                vib = 1 + 0.004 * np.sin(2 * np.pi * rate * t + rng.random() * 6.28)
                f = hz(m) * 2 ** (cents / 1200) * vib
                v = additive_saw(f, max_hz=6000, brightness=1.4) * e
                add(out, start + rng.random() * 0.08, v, pan=float(np.clip(rng.normal(0, 0.45), -0.9, 0.9)))
    breath = lowpass(highpass(rng.normal(0, 1, (N, 2)), 900), 5000) * 0.015
    out = formants(out + breath, [(780, 1.2, 0.7), (1150, 0.8, 0.6), (2900, 0.45, 0.5)])
    out = peak_eq(out, 1300, -3, 2.0)
    # A reversed swell into each chord change: the next chord's tail, played backwards.
    swells = buf()
    for i in range(4):
        _, _, voicing = CHORDS[(i + 1) % 4]
        n = int(2.0 * SR)
        t = t_of(n)
        hit = sum(additive_saw(np.full(n, hz(m)), max_hz=5000, brightness=1.5) for m in voicing) * np.exp(-t * 2.2)
        rev = formants(hit[::-1].copy(), [(780, 1.2, 0.7), (1150, 0.8, 0.6)])
        add(swells, (i + 1) * 8 * BEAT - 2.0, rev * 0.5)
    out = out + reverb(swells, 3.0, 0.7, damp_hz=4000, seed=9)
    out = wobble(out, 0.55, 13, flutter_ms=0.05)
    return reverb(out, 4.5, 0.48, damp_hz=4500, seed=8)


# (file, generator, target RMS in dBFS). Levels follow the original set's balance, so switching
# soundtracks doesn't change how loud the game is; the vox sits back to leave the violin in front.
STEMS = [
    ("01_kick", kick, -21.0),
    ("02_hats", hats, -36.0),
    ("03_bass", bass, -20.0),
    ("04_perc", perc, -30.0),
    ("05_pad", pad, -21.0),
    ("06_arp", arp, -24.0),
    ("07_lead", lead, -19.0),
    ("08_vox", vox, -26.0),
]
PEAK_LIMIT = 0.7


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "main", "assets", "music", "dead_channel")
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, fn, rms_db in STEMS:
            x = fn()
            assert x.shape == (N, 2), (name, x.shape)
            x = x - x.mean(axis=0, keepdims=True)
            x = x * (10 ** (rms_db / 20) / (np.sqrt((x ** 2).mean()) or 1.0))
            if np.abs(x).max() > PEAK_LIMIT:  # a transient-heavy part: soften the peaks, keep the level
                x = np.tanh(x / PEAK_LIMIT) * PEAK_LIMIT
            wav = os.path.join(tmp, name + ".wav")
            write_wav(wav, x)
            ogg = os.path.join(out_dir, name + ".ogg")
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", ogg], check=True)
            print(f"{ogg}: {N / SR:.2f}s")


if __name__ == "__main__":
    main()
