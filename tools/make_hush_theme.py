#!/usr/bin/env python3
"""Synthesises HUSH's theme: the sound of the city being listened to.

Minimal industrial horror, from HUSH's side. A building-sized air handler that breathes, a mains
transformer hum, a clean test tone that is always on, and every four bars a struck steel beam
ringing out in a huge concrete space. Twice a loop a swell of sound is sucked backwards into
silence. A servo turns toward you. Faint telemetry ticks underneath, like something taking notes.

The game plays it whenever HUSH holds the frequency: under its prologue, and around the signal it
guards, where it grows as the runner closes in and pushes the pirate music down.

One 16-bar loop at 88 BPM (about 43.6 s), so it sits on the same grid as Dead Channel; everything
that rings wraps around the loop point, so it loops without a seam. Kept quiet and sparse on
purpose: it should feel like pressure, not a song. Its low end carries harmonics above 150 Hz so
open-ear glasses can still play it.

Usage: python3 tools/make_hush_theme.py [out.ogg]   (needs numpy and ffmpeg)
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
BARS = 16
N = int(round(BARS * 4 * BEAT * SR))
T = np.arange(N) / SR
rng = np.random.default_rng(933)


def spectral(x, response):
    X = np.fft.rfft(x, axis=0)
    f = np.maximum(np.fft.rfftfreq(len(x), 1 / SR), 1e-3)
    H = response(f)
    return np.fft.irfft(X * (H[:, None] if x.ndim > 1 else H), n=len(x), axis=0)


def lowpass(x, fc, order=2):
    return spectral(x, lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order)))


def highpass(x, fc, order=2):
    return spectral(x, lambda f: 1 / np.sqrt(1 + (fc / f) ** (2 * order)))


def add(out, start_s, sig, pan=0.0):
    """Mixes into the loop at start_s, wrapping around the end."""
    i = int(round(start_s * SR)) % N
    if sig.ndim == 1:
        sig = np.stack([sig * np.cos((pan + 1) * np.pi / 4), sig * np.sin((pan + 1) * np.pi / 4)], axis=1)
    n = len(sig)
    first = min(n, N - i)
    out[i : i + first] += sig[:first]
    if n > first:
        out[: n - first] += sig[first:]


def reverb(x, decay_s, wet, damp_hz, seed):
    """Circular convolution with a decorrelated noise tail: the tail wraps onto the loop start."""
    r = np.random.default_rng(seed)
    n = min(int(decay_s * 1.5 * SR), N - 1)
    t = np.arange(n) / SR
    ir = r.normal(0, 1, (n, 2)) * np.exp(-6.9 * t / decay_s)[:, None]
    ir[: int(0.03 * SR)] = 0
    ir = lowpass(ir, damp_hz, 1)
    ir /= np.sqrt((ir ** 2).sum(axis=0, keepdims=True)) + 1e-12
    full = np.zeros((N, 2))
    full[:n] = ir
    tail = np.fft.irfft(np.fft.rfft(x, axis=0) * np.fft.rfft(full, axis=0), n=N, axis=0)
    return x * (1 - wet) + tail * wet * 1.4


def loopf(f):
    """Nearest frequency that completes whole cycles per loop: a steady tone must, or the seam clicks."""
    return round(f * N / SR) / (N / SR)


def bars(b):
    return b * 4 * BEAT


def cyc(per_loop, phase=0.0):
    """A modulation that completes whole cycles per loop, so it never puts a seam in it."""
    return np.sin(2 * np.pi * per_loop * np.arange(N) / N + phase)


def air_handler():
    """The building breathing: band-limited noise that swells twice a loop."""
    x = rng.normal(0, 1, (N, 2))
    x = lowpass(highpass(x, 90, 2), 750, 2)
    breath = 0.55 + 0.45 * (0.5 + 0.5 * cyc(2, -np.pi / 2)) ** 1.5
    return x * breath[:, None] * 0.5


def transformer():
    """Mains hum on a low E, with the harmonics a small speaker can still play."""
    out = np.zeros((N, 2))
    for ch, beat_hz in ((0, 0.0), (1, 4 / (N / SR))):  # four slow beats per loop between the ears
        f0 = loopf(41.2) + beat_hz
        sig = np.zeros(N)
        for k, a in ((1, 0.8), (2, 0.6), (3, 0.55), (4, 0.5), (5, 0.36), (6, 0.3), (8, 0.22), (10, 0.12)):
            sig += a * np.sin(2 * np.pi * f0 * k * T + k * 0.7)
        hum120 = 0.18 * np.sin(2 * np.pi * loopf(120) * T) + 0.07 * np.sin(2 * np.pi * loopf(240) * T)
        out[:, ch] = np.tanh((sig * 0.5 + hum120) * 1.6)
    swell = 0.7 + 0.3 * (0.5 + 0.5 * cyc(1, np.pi))
    return out * swell[:, None] * 0.35


def test_tone():
    """HUSH's own voice: a clean, beating test tone that never quite stops."""
    f = loopf(1056)
    a = np.sin(2 * np.pi * f * T) + np.sin(2 * np.pi * (f + 40 / (N / SR)) * T)  # forty slow beats per loop
    gate = np.clip(0.5 + 0.5 * cyc(4, 0.3), 0, 1) ** 3
    sig = a * gate * 0.025
    return np.stack([sig, sig * 0.8], axis=1)


def steel(freq_scale=1.0, decay=1.0):
    """A struck steel beam: inharmonic partials, long decays, a dull thud underneath."""
    n = int(7.5 * SR)
    t = np.arange(n) / SR
    partials = [(73, 1.0, 1.6), (191, 0.6, 2.4), (334, 0.42, 2.8), (517, 0.3, 3.4), (742, 0.18, 4.4), (1093, 0.09, 6.0)]
    sig = sum(a * np.sin(2 * np.pi * f * freq_scale * t + rng.random() * 6.28) * np.exp(-t * d / decay) for f, a, d in partials)
    thud = np.sin(2 * np.pi * np.cumsum(38 + 40 * np.exp(-t * 18)) / SR) * np.exp(-t * 5)
    return (sig * 0.5 + thud * 0.8) * np.minimum(t / 0.002, 1)


def servo():
    """A servo turning toward you: a filtered whine gliding up, then settling."""
    n = int(2.2 * SR)
    t = np.arange(n) / SR
    f = 280 + 520 * (1 - np.exp(-t * 2.2)) + 6 * np.sin(2 * np.pi * 9 * t)
    phase = 2 * np.pi * np.cumsum(f) / SR
    tone = sum(np.sin(k * phase) / k for k in range(1, 9))
    env = np.minimum(t / 0.25, 1) * np.clip((2.2 - t) / 0.6, 0, 1)
    return lowpass(tone * env, 1800, 2) * 0.35


def erasure():
    """A swell of noise and tone sucked backwards into silence, ending on a hard cut."""
    n = int(3.0 * SR)
    t = np.arange(n) / SR
    burst = rng.normal(0, 1, n) * np.exp(-t * 1.6) + np.sin(2 * np.pi * 330 * t) * np.exp(-t * 1.2) * 0.6
    burst = lowpass(highpass(burst, 200), 5000)
    rev = burst[::-1].copy()
    rev[-int(0.004 * SR):] *= np.linspace(1, 0, int(0.004 * SR))
    return rev * 0.6


def telemetry():
    """Faint crushed blips on the sixteenth grid, like something taking notes."""
    out = np.zeros((N, 2))
    step = BEAT / 4
    for k in range(BARS * 16):
        if rng.random() < 0.16:
            n = int(0.03 * SR)
            t = np.arange(n) / SR
            f = rng.choice([2093, 2637, 3136, 4186])
            blip = np.sign(np.sin(2 * np.pi * f * t)) * np.exp(-t * 140)
            add(out, k * step, blip * (0.5 + 0.5 * rng.random()), pan=float(rng.uniform(-0.8, 0.8)))
    out = np.round(highpass(out, 1500) * 24) / 24
    return out * 0.05


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "main", "assets", "music", "hush", "hush_theme.ogg")
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    bed = air_handler() + transformer() + test_tone()

    hits = np.zeros((N, 2))
    for b in (0, 4, 8, 12):
        add(hits, bars(b), steel(1.0 if b % 8 == 0 else 0.84), pan=-0.25 if b % 8 else 0.2)
    for b in (2.5, 10.5):  # distant answers, smaller and further away
        add(hits, bars(b), steel(1.33, 0.6) * 0.3, pan=0.6 if b < 8 else -0.6)
    hits = reverb(hits, 6.5, 0.62, damp_hz=2600, seed=1)

    moves = np.zeros((N, 2))
    for b in (6, 14):
        add(moves, bars(b) - 0.4, servo(), pan=0.5 if b == 6 else -0.5)
    erase = np.zeros((N, 2))
    for b in (3, 11):
        sig = erasure()
        add(erase, bars(b) - len(sig) / SR, sig, pan=0.0)
    erase = reverb(erase, 1.5, 0.25, damp_hz=4000, seed=2)

    # After each erasure, a breath of near-silence: everything but the hum drops away.
    dip = np.ones(N)
    for b in (3, 11):
        i0 = int(bars(b) * SR)
        n = int(1.6 * SR)
        shape = 1 - 0.85 * np.exp(-np.arange(n) / SR * 2.0)
        idx = (i0 + np.arange(n)) % N
        dip[idx] = np.minimum(dip[idx], shape)

    mix = bed * dip[:, None] + hits + reverb(moves, 2.5, 0.35, 3500, 3) * dip[:, None] + erase + telemetry()
    mix = mix - mix.mean(axis=0, keepdims=True)
    mix = np.tanh(mix * 1.2) / np.tanh(1.2)
    rms = np.sqrt((mix ** 2).mean())
    mix *= 10 ** (-22 / 20) / rms
    if np.abs(mix).max() > 0.7:
        mix = np.tanh(mix / 0.7) * 0.7

    pcm = (np.clip(mix, -1, 1) * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "hush.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", out_path], check=True)
    print(f"{out_path}: {N / SR:.2f}s")


if __name__ == "__main__":
    main()
