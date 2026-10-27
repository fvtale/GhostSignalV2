#!/usr/bin/env python3
"""Synthesises the eight placeholder stems for "The Last Eight Bars".

These exist so the app is playable before the real stems are ready. Each stem is one layer of a
118 BPM, 8-bar loop in A minor (Am - F - C - G, two bars each), exactly the same length, so
they stack in sync. Replace them with exports from your DAW using the same tempo, key-agnostic,
same bar count (see docs/AUDIO.md).

Usage: python3 tools/make_placeholder_stems.py [out_dir]   (needs numpy and ffmpeg)
"""

import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 48_000
BPM = 118.0
BEAT = 60.0 / BPM
BARS = 8
N = int(round(BARS * 4 * BEAT * SR))
rng = np.random.default_rng(118)

CHORDS = [  # (root midi, chord tones) per two bars
    (45, [57, 60, 64]),  # Am
    (41, [53, 57, 60]),  # F
    (48, [55, 60, 64]),  # C
    (43, [55, 59, 62]),  # G
]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def chord_at(beat):
    return CHORDS[int(beat // 8) % 4]


def buf():
    return np.zeros((N, 2))


def add(out, start_s, sig, pan=0.0):
    """Mixes mono `sig` into stereo `out` at `start_s`, wrapping around the loop end."""
    i = int(round(start_s * SR)) % N
    left = np.cos((pan + 1) * np.pi / 4)
    right = np.sin((pan + 1) * np.pi / 4)
    n = len(sig)
    first = min(n, N - i)
    out[i : i + first, 0] += sig[:first] * left
    out[i : i + first, 1] += sig[:first] * right
    if n > first:
        out[: n - first, 0] += sig[first:] * left
        out[: n - first, 1] += sig[first:] * right


def env(length_s, attack=0.003, decay=8.0):
    t = np.arange(int(length_s * SR)) / SR
    return np.minimum(t / attack, 1.0) * np.exp(-t * decay), t


def onepole_lp(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    z = 0.0
    for i, v in enumerate(x):
        z = v * (1 - a) + z * a
        y[i] = z
    return y


def resonator(x, freq, q):
    r = np.exp(-np.pi * freq / q / SR)
    c = 2 * r * np.cos(2 * np.pi * freq / SR)
    r2 = r * r
    y = np.empty_like(x)
    y1 = y2 = 0.0
    for i, v in enumerate(x):
        y0 = v + c * y1 - r2 * y2
        y[i] = y0
        y2, y1 = y1, y0
    return y * (1 - r)


def saw(freq, t, detune_cents=0.0):
    f = freq * 2 ** (detune_cents / 1200)
    return 2 * ((t * f) % 1.0) - 1


def kick():
    out = buf()
    e, t = env(0.45, 0.001, 9.0)
    pitch = 48 + 110 * np.exp(-t * 35)
    phase = 2 * np.pi * np.cumsum(pitch) / SR
    hit = np.sin(phase) * e + rng.normal(0, 1, len(t)) * np.exp(-t * 300) * 0.15
    for b in range(BARS * 4):
        add(out, b * BEAT, hit)
    return out


def hats():
    out = buf()
    noise = rng.normal(0, 1, int(0.25 * SR))
    hp = np.diff(noise, prepend=0)
    for step in range(BARS * 16):
        pos = step * BEAT / 4
        if step % 4 == 2:
            e, _ = env(0.09, 0.0005, 45)
            add(out, pos, hp[: len(e)] * e * 0.9, pan=0.2)
        elif step % 2 == 1:
            e, _ = env(0.04, 0.0005, 90)
            add(out, pos, hp[: len(e)] * e * 0.35, pan=-0.3 if step % 4 == 1 else 0.35)
        if step % 32 == 30:  # open hat into the next bar pair
            e, _ = env(0.25, 0.0005, 12)
            add(out, pos, hp[: len(e)] * e * 0.5, pan=0.1)
    return out


def bass():
    out = buf()
    pattern = [0, 0.75, 1.5, 2.0, 2.75, 3.5]  # syncopated eighths/dotted eighths per bar
    for bar in range(BARS):
        for p in pattern:
            beat = bar * 4 + p
            root, _ = chord_at(beat)
            note = root + (12 if p in (1.5, 3.5) else 0)
            e, t = env(0.32, 0.004, 7.0)
            tone = saw(hz(note), t) * 0.6 + np.sin(2 * np.pi * hz(note) * t) * 0.6
            add(out, beat * BEAT, tone * e)
    mono = onepole_lp(onepole_lp(out[:, 0], 700), 900)
    return np.stack([mono, mono], axis=1)


def perc():
    out = buf()
    noise = rng.normal(0, 1, int(0.2 * SR))
    for bar in range(BARS):
        for beat in (1, 3):  # claps on 2 and 4: three micro-bursts
            for k, delay in enumerate((0.0, 0.011, 0.022)):
                e, _ = env(0.12 if k == 2 else 0.02, 0.0005, 30 if k == 2 else 120)
                add(out, (bar * 4 + beat) * BEAT + delay, np.diff(noise[: len(e)], prepend=0) * e * 0.5, pan=0.05)
        if bar % 2 == 0:  # Mari's motif: boom, ka, boom-boom, ka
            for onset, f in ((0, 520), (1, 880), (1.5, 520), (2, 520), (3, 880)):
                e, t = env(0.07, 0.0005, 55)
                add(out, (bar * 4 + onset) * BEAT + BEAT * 0.5, np.sin(2 * np.pi * f * t) * e * 0.7, pan=-0.35)
    return out


def pad():
    out = buf()
    for i in range(4):
        root, tones = CHORDS[i]
        start = i * 8 * BEAT
        length = 8 * BEAT
        t = np.arange(int(length * SR)) / SR
        shape = np.minimum(t / 0.6, 1.0) * np.minimum((length - t) / 0.4, 1.0)
        left = sum(saw(hz(m), t, -7) for m in tones) / 3
        right = sum(saw(hz(m), t, +7) for m in tones) / 3
        out[int(start * SR) : int(start * SR) + len(t), 0] += left * shape
        out[int(start * SR) : int(start * SR) + len(t), 1] += right * shape
    out[:, 0] = onepole_lp(onepole_lp(out[:, 0], 1100), 1500)
    out[:, 1] = onepole_lp(onepole_lp(out[:, 1], 1100), 1500)
    return out


def arp():
    out = buf()
    for step in range(BARS * 16):
        beat = step / 4
        _, tones = chord_at(beat)
        seq = [tones[0] + 12, tones[1] + 12, tones[2] + 12, tones[0] + 24]
        note = seq[step % 4] if (step // 4) % 2 == 0 else seq[3 - step % 4]
        e, t = env(0.16, 0.002, 18)
        tone = np.sign(np.sin(2 * np.pi * hz(note) * t)) * 0.5 + np.sin(2 * np.pi * hz(note) * t) * 0.5
        add(out, beat * BEAT, tone * e, pan=0.6 if step % 2 else -0.6)
    out[:, 0] = onepole_lp(out[:, 0], 3500)
    out[:, 1] = onepole_lp(out[:, 1], 3500)
    return out


MELODY = [  # (midi, beats), 32 beats
    (69, 2), (72, 1), (76, 1), (74, 2), (72, 2),
    (69, 1), (72, 1), (77, 2), (76, 3), (None, 1),
    (67, 2), (72, 1), (76, 1), (79, 2), (76, 2),
    (74, 1), (71, 1), (67, 2), (69, 4),
]


def lead():
    out = buf()
    beat = 0.0
    for note, dur in MELODY:
        if note is not None:
            length = dur * BEAT
            t = np.arange(int(length * SR)) / SR
            vib = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * t) * np.minimum(t / 0.3, 1)
            phase = np.cumsum(hz(note) * vib) / SR
            tone = (2 * (phase % 1.0) - 1) * 0.5 + np.sin(2 * np.pi * phase) * 0.5
            shape = np.minimum(t / 0.02, 1.0) * np.minimum((length - t) / 0.05, 1.0).clip(0, 1)
            add(out, beat * BEAT, tone * shape)
        beat += dur
    mono = onepole_lp(onepole_lp(out[:, 0] + out[:, 1], 2600), 3200) * 0.5
    # A short slap delay to the right for width.
    d = int(0.09 * SR)
    right = mono.copy()
    right[d:] += mono[:-d] * 0.35
    return np.stack([mono, right], axis=1)


def vox():
    out = buf()
    for bar in range(BARS):
        for k, off in enumerate((0.5, 1.5, 2.5, 3.25)):
            beat = bar * 4 + off
            root, tones = chord_at(beat)
            note = tones[k % 3] + 12
            length = 0.22 if k < 3 else 0.4
            t = np.arange(int(length * SR)) / SR
            glottal = saw(hz(note), t) + 0.3 * rng.normal(0, 1, len(t))
            shape = np.minimum(t / 0.015, 1.0) * np.exp(-t * (6 if k == 3 else 12))
            add(out, beat * BEAT, glottal * shape, pan=-0.25 if k % 2 else 0.25)
    for ch in (0, 1):
        x = out[:, ch]
        out[:, ch] = resonator(x, 780, 9) * 2.2 + resonator(x, 1150, 11) * 1.6 + resonator(x, 2600, 14) * 0.6
    # Ghostly feedback echo, a dotted eighth.
    d = int(BEAT * 0.75 * SR)
    for i in range(3):
        out[d * (i + 1) :] += out[: N - d * (i + 1)] * (0.35 / (i + 1))
    return out


STEMS = [
    ("01_kick", kick, 0.5),
    ("02_hats", hats, 0.28),
    ("03_bass", bass, 0.42),
    ("04_perc", perc, 0.3),
    ("05_pad", pad, 0.3),
    ("06_arp", arp, 0.26),
    ("07_lead", lead, 0.3),
    ("08_vox", vox, 0.3),
]


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "music")
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, fn, peak in STEMS:
            x = fn()
            x = x / (np.abs(x).max() or 1.0) * peak
            wav = os.path.join(tmp, name + ".wav")
            write_wav(wav, x)
            ogg = os.path.join(out_dir, name + ".ogg")
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", ogg], check=True)
            print(f"{ogg}: {N / SR:.2f}s")


if __name__ == "__main__":
    main()
