#!/usr/bin/env python3
"""Synthesises the opening ambience: a moody, rain-soaked synth pad loop
in the spirit of early-80s analog film scores (detuned additive pads,
slow filter-like swells) under steady city rain.

48-second seamless loop: four 12-second chords (Fm9, Dbmaj9, Abmaj9,
Cm9) crossfading into each other, rain throughout. Pure stdlib; WAV via
the wave module, converted to Ogg Vorbis with ffmpeg when present.

Usage: python3 tools/build_ambience.py [out.ogg]
"""

import math
import os
import random
import struct
import subprocess
import sys
import tempfile
import wave

SR = 44100
DUR = 48.0
N = int(SR * DUR)
CHORD_DUR = 12.0
PEAK_LIMIT = 0.65

# (chord name, MIDI notes) — low, wide voicings; no lead, all atmosphere.
CHORDS = [
    ("Fm9", [29, 41, 53, 56, 60, 63]),
    ("Dbmaj9", [25, 37, 49, 53, 57, 60]),
    ("Abmaj9", [32, 44, 48, 55, 60, 63]),
    ("Cm9", [24, 36, 48, 51, 55, 58]),
]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def pad_note(midi, dur, detune_cents, gain):
    """One detuned additive pad voice with a slow swell in and out."""
    n = int(dur * SR)
    f = hz(midi) * 2 ** (detune_cents / 1200)
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        env = min(1.0, t / 3.5) * min(1.0, (dur - t) / 3.5)
        breathe = 0.85 + 0.15 * math.sin(2 * math.pi * 0.07 * t + midi)
        v = (math.sin(2 * math.pi * f * t)
             + 0.5 * math.sin(2 * math.pi * f * 2 * t)
             + 0.22 * math.sin(2 * math.pi * f * 3 * t)
             + 0.1 * math.sin(2 * math.pi * f * 4 * t))
        out[i] = v * env * breathe * gain
    return out


def add_wrap(left, right, sig, start_s, pan, gain):
    lg = gain * math.cos((pan + 1) * math.pi / 4)
    rg = gain * math.sin((pan + 1) * math.pi / 4)
    i0 = int(round(start_s * SR)) % N
    for j, s in enumerate(sig):
        i = (i0 + j) % N
        left[i] += s * lg
        right[i] += s * rg


def render():
    left = [0.0] * N
    right = [0.0] * N
    # Chords start one chord-length apart and overlap via their swells;
    # the last chord wraps into the first, closing the loop.
    for idx, (_, notes) in enumerate(CHORDS):
        start = idx * CHORD_DUR
        for j, midi in enumerate(notes):
            pan = -0.5 + j / max(1, len(notes) - 1)
            for detune in (-6, 5):
                sig = pad_note(midi, CHORD_DUR + 7.0, detune, 0.16)
                add_wrap(left, right, sig, start, pan, 1.0)
    # A low pulse under everything, like a distant city hum.
    for i in range(N):
        t = i / SR
        hum = 0.05 * math.sin(2 * math.pi * hz(29) * t) * (0.8 + 0.2 * math.sin(2 * math.pi * t / DUR))
        left[i] += hum
        right[i] += hum
    # Rain: smoothed noise bed + sparse droplet ticks.
    rng = random.Random(707)
    yl = yr = 0.0
    a_bed = 1 - math.exp(-2 * math.pi * 900 / SR)
    for i in range(N):
        yl += a_bed * ((rng.random() * 2 - 1) - yl)
        yr += a_bed * ((rng.random() * 2 - 1) - yr)
        left[i] += yl * 0.16
        right[i] += yr * 0.16
    for _ in range(260):
        start = rng.random() * DUR
        n = int(0.03 * SR)
        f = rng.uniform(1800, 4200)
        drop = [math.sin(2 * math.pi * f * (i / SR)) * math.exp(-(i / SR) * 160) * 0.10
                for i in range(n)]
        add_wrap(left, right, drop, start, rng.uniform(-0.8, 0.8), 1.0)
    return left, right


def finish(left, right):
    total = sum(v * v for v in left) + sum(v * v for v in right)
    rms = math.sqrt(total / (2 * N)) or 1.0
    scale = 10 ** (-26 / 20) / rms
    peak = 0.0
    for ch in (left, right):
        for i in range(N):
            ch[i] *= scale
            peak = max(peak, abs(ch[i]))
    if peak > PEAK_LIMIT:
        for ch in (left, right):
            for i in range(N):
                ch[i] = math.tanh(ch[i] / PEAK_LIMIT) * PEAK_LIMIT
    fade = int(0.01 * SR)
    for ch in (left, right):
        for i in range(fade):
            r = i / fade
            ch[i] *= r
            ch[N - 1 - i] *= r
    return peak


def main():
    dest = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..",
        "app", "src", "main", "assets", "music", "ambience", "rain_city.ogg")
    dest = os.path.normpath(dest)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    left, right = render()
    peak = finish(left, right)
    pcm = bytearray()
    pack = struct.Struct("<hh").pack
    for i in range(N):
        pcm += pack(int(max(-1, min(1, left[i])) * 32767),
                    int(max(-1, min(1, right[i])) * 32767))
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "rain_city.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(bytes(pcm))
        if subprocess.run(["ffmpeg", "-version"], capture_output=True).returncode == 0:
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                            "-c:a", "libvorbis", "-q:a", "5", dest], check=True)
            r = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                                "format=duration", "-of", "default=noprint_wrappers=1",
                                "--", dest], capture_output=True, text=True, check=True)
            print(f"rain_city: {r.stdout.strip()} peak={peak:.3f} -> {dest}")
        else:
            fallback = dest.rsplit(".", 1)[0] + ".wav"
            os.replace(wav, fallback)
            print(f"UNRESOLVED: ffmpeg missing; wrote {fallback}")


if __name__ == "__main__":
    main()
