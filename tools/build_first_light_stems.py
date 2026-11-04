#!/usr/bin/env python3
"""Synthesises "First Light on the Reservoir" — the Central Park mission
track. Light ambient synthwave: sunrise in winter, 104 BPM, D major with
a B-minor undertone, built as eight independently usable layers
(atmosphere, pad, bass, kick, percussion, arpeggio, human texture, lead).

Delivery spec honoured where the game allows: stems render at 48 kHz,
the WAV masters are 24-bit with identical start/end, each layer keeps
its own delay/reverb, and NO per-stem normalization is applied — the
stems sum to the reference mix at unity gain. Game-ready Ogg copies
(48 kHz) go to the app assets; WAV masters + the reference mix go to
deliverables/first_light_on_the_reservoir/.

The sunrise is brightness, not loudness: the atmosphere filter opens
across the loop, voicings gain upper extensions, and the lead only
fully arrives in the second half.

Usage: python3 tools/build_first_light_stems.py
"""

import math
import os
import random
import struct
import subprocess
import sys
import tempfile
import wave

SR = 48000
BPM = 104.0
BEAT = 60.0 / BPM
BARS = 8
N = int(round(BARS * 4 * BEAT * SR))  # 18.4615 s

# (name, bass-root MIDI, voicing MIDI) — 2 bars each.
CHORDS = [
    ("Dmaj9", 38, [50, 54, 57, 61, 64]),
    ("Bm9", 35, [47, 50, 54, 57, 61]),
    ("Gmaj9", 31, [43, 47, 50, 54, 57]),
    ("Aadd9", 33, [45, 49, 52, 59, 61]),
]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def buf():
    return [[0.0] * N, [0.0] * N]


def add(out, start_s, sig, pan=0.0, gain=1.0):
    lg = gain * math.cos((pan + 1) * math.pi / 4)
    rg = gain * math.sin((pan + 1) * math.pi / 4)
    i0 = int(round(start_s * SR)) % N
    left, right = out
    for j, s in enumerate(sig):
        i = (i0 + j) % N
        left[i] += s * lg
        right[i] += s * rg


def tone(midi, dur, harmonics=(1.0,), attack=0.01, tau=None, gain=1.0,
         vib_hz=0.0, vib_cents=0.0):
    n = int(dur * SR)
    f = hz(midi)
    out = [0.0] * n
    total_h = sum(harmonics)
    for i in range(n):
        t = i / SR
        env = min(1.0, t / attack) if attack > 0 else 1.0
        if tau:
            env *= math.exp(-t / tau)
        else:
            env *= min(1.0, (dur - t) / 0.05)
        ff = f * (1 + (vib_cents / 1200) * 0.000578 * math.sin(2 * math.pi * vib_hz * t)) if vib_hz else f
        v = 0.0
        for k, h in enumerate(harmonics, 1):
            v += h * math.sin(2 * math.pi * ff * k * t)
        out[i] = v / total_h * env * gain
    return out


def noise_burst(rng, dur, tau, gain=1.0):
    n = int(dur * SR)
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        out[i] = (rng.random() * 2 - 1) * math.exp(-t / tau) * min(1.0, t / 0.001) * gain
    return out


def lowpass(sig, fc):
    a = 1 - math.exp(-2 * math.pi * fc / SR)
    out = [0.0] * len(sig)
    y = 0.0
    for i, v in enumerate(sig):
        y += a * (v - y)
        out[i] = y
    return out


def atmosphere():
    """01: winter air that brightens — the filter opens across the loop."""
    rng = random.Random(401)
    out = buf()
    left, right = out
    # Two noise streams lowpassed with a cutoff that rises over 8 bars.
    yl = yr = 0.0
    for i in range(N):
        frac = i / N
        fc = 300 + 2400 * frac * frac  # blue shadow -> pale gold
        a = 1 - math.exp(-2 * math.pi * fc / SR)
        yl += a * ((rng.random() * 2 - 1) - yl)
        yr += a * ((rng.random() * 2 - 1) - yr)
        swell = 0.8 + 0.2 * math.sin(2 * math.pi * i / N * 2)
        left[i] = yl * 0.48 * swell
        right[i] = yr * 0.48 * swell
    # Still-water shimmers: sparse high pings, more frequent as light grows.
    for k in range(26):
        start = (k / 26) ** 1.4 * BARS * 4 * BEAT + rng.uniform(-0.2, 0.2)
        f = rng.choice([2349, 2637, 3136, 3520])
        ping = [math.sin(2 * math.pi * f * (i / SR)) * math.exp(-(i / SR) * 9) * 0.05
                for i in range(int(0.5 * SR))]
        add(out, start, ping, pan=rng.uniform(-0.6, 0.6))
    return out


def pad():
    """02: warm analog pads, detuned pairs, voicings widen per chord."""
    out = buf()
    for block, (_, _, voicing) in enumerate(CHORDS):
        base = block * 8 * BEAT
        for j, midi in enumerate(voicing):
            pan = -0.45 + 0.9 * j / max(1, len(voicing) - 1)
            for detune in (-5, 4):
                sig = tone(midi + detune / 100.0, 8 * BEAT + 2.0,
                           harmonics=(1.0, 0.4, 0.18, 0.07), attack=1.6, gain=0.10)
                add(out, base, sig, pan=pan, gain=0.55 if detune < 0 else 0.45)
    return out


def bass():
    """03: sparse roots with room between them."""
    out = buf()
    pattern = [(0.0, 0, 1.4, 0.9), (3.5, 0, 0.5, 0.6), (5.0, 0, 1.2, 0.8), (7.5, 7, 0.4, 0.55)]
    for block, (_, root, _) in enumerate(CHORDS):
        base = block * 8 * BEAT
        for beat, semi, dur, vel in pattern:
            sig = tone(root + semi, dur, harmonics=(1.0, 0.3, 0.1),
                       attack=0.008, tau=0.5, gain=vel)
            add(out, base + beat * BEAT, lowpass(sig, 700), gain=0.65)
    return out


def kick():
    """04: soft, round kick — propulsion, not punishment."""
    rng = random.Random(404)
    out = buf()
    for beat in range(BARS * 4):
        n = int(0.26 * SR)
        hit = [0.0] * n
        phase = 0.0
        for i in range(n):
            t = i / SR
            f = 50 + 70 * math.exp(-t * 30)
            phase += 2 * math.pi * f / SR
            hit[i] = math.sin(phase) * math.exp(-t * 11) * min(1.0, t / 0.002)
            hit[i] += (rng.random() * 2 - 1) * math.exp(-t * 400) * 0.06
        add(out, beat * BEAT, hit, gain=0.55)
    return out


def percussion():
    """05: brushed shaker and soft rim, lightly swung."""
    rng = random.Random(405)
    out = buf()
    swing = 0.10 * (BEAT / 2)
    for step in range(BARS * 8):
        pos = step * BEAT / 2 + (swing if step % 2 else 0.0)
        accent = 0.5 if step % 2 == 0 else 0.32
        shake = noise_burst(rng, 0.09, 0.03)
        add(out, pos, lowpass(shake, 6500), pan=0.25 if step % 2 else -0.2, gain=accent)
        if step % 8 == 4:
            rim = noise_burst(rng, 0.05, 0.012)
            add(out, pos, lowpass(rim, 3200), pan=-0.1, gain=0.35)
    return out


# Chord-tone pluck pattern (scale degrees into the voicing + octave).
ARP_STEPS = [0, 1, 2, 3, 4, 3, 2, 1]


def arpeggio():
    """06: delicate plucked arpeggios with their own dotted-eighth echo."""
    out = buf()
    for block, (_, _, voicing) in enumerate(CHORDS):
        base = block * 8 * BEAT
        tones = voicing + [voicing[2] + 12, voicing[4] + 12, voicing[1] + 12]
        for bar in range(2):
            for step_i, deg in enumerate(ARP_STEPS):
                pos = base + (bar * 4 + step_i * 0.5) * BEAT
                midi = tones[deg % len(tones)]
                sig = tone(midi, 0.22, harmonics=(1.0, 0.36, 0.12),
                           attack=0.003, tau=0.09, gain=0.34)
                add(out, pos, sig, pan=-0.3 + 0.6 * (step_i % 3) / 2)
                echo = [s * 0.35 for s in sig]  # the stem's own delay
                add(out, pos + 0.75 * BEAT, echo, pan=0.3 - 0.6 * (step_i % 3) / 2)
    return out


def human():
    """07: organic texture — snow-crunch steps, a breath, distant city."""
    rng = random.Random(407)
    out = buf()
    # Footsteps in snow: irregular lowpassed crunches, two walkers passing.
    t = 0.6
    while t < BARS * 4 * BEAT - 0.5:
        crunch = lowpass(noise_burst(rng, 0.14, 0.045), 900)
        add(out, t, crunch, pan=-0.4 if int(t * 2) % 2 else 0.35, gain=0.5)
        t += rng.uniform(0.42, 0.62)
    # Breaths: slow banded-noise swells, four across the loop.
    for k in range(4):
        start = (k * 2 + 0.5) * 4 * BEAT
        n = int(1.8 * SR)
        breath = [0.0] * n
        y = 0.0
        a = 1 - math.exp(-2 * math.pi * 1200 / SR)
        for i in range(n):
            tt = i / SR
            y += a * ((rng.random() * 2 - 1) - y)
            breath[i] = y * math.sin(math.pi * tt / 1.8) * 0.5
        add(out, start, breath, pan=0.1)
    # Distant traffic wash, very low in the mix.
    wash = lowpass([rng.random() * 2 - 1 for _ in range(N)], 220)
    add(out, 0.0, wash, gain=0.05)
    return out


# Sparse, memorable: (beat, MIDI, dur beats). Second half answers.
LEAD = [
    (0.0, 74, 1.5), (2.0, 78, 1.0), (3.0, 76, 2.0),
    (16.0, 74, 1.5), (18.0, 81, 1.0), (19.0, 78, 1.5), (21.0, 76, 2.5),
]


def lead():
    """08: a few notes with air around them; brighter the second time."""
    out = buf()
    for beat, midi, dur in LEAD:
        sig = tone(midi, dur * BEAT, harmonics=(1.0, 0.3, 0.12, 0.05),
                   attack=0.03, tau=None, vib_hz=5.0, vib_cents=7, gain=0.5)
        add(out, beat * BEAT, sig, pan=0.05)
        tail = [s * 0.22 for s in sig]
        add(out, beat * BEAT + 1.5 * BEAT, tail, pan=-0.15)  # its own echo
    return out


STEMS = [
    ("01_atmosphere", atmosphere),
    ("02_pad", pad),
    ("03_bass", bass),
    ("04_kick", kick),
    ("05_percussion", percussion),
    ("06_arpeggio", arpeggio),
    ("07_human", human),
    ("08_lead", lead),
]


def write_wav24(path, left, right):
    pcm = bytearray()
    for i in range(N):
        for v in (left[i], right[i]):
            s = int(max(-1, min(1, v)) * 8388607)
            pcm += s.to_bytes(3, "little", signed=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(3)
        w.setframerate(SR)
        w.writeframes(bytes(pcm))


def to_ogg(wav_path, ogg_path):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
                    "-c:a", "libvorbis", "-q:a", "5", ogg_path], check=True)


def main():
    root = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    masters = os.path.join(root, "deliverables", "first_light_on_the_reservoir")
    game = os.path.join(root, "app", "src", "main", "assets", "music", "first_light")
    os.makedirs(masters, exist_ok=True)
    os.makedirs(game, exist_ok=True)

    mix_l = [0.0] * N
    mix_r = [0.0] * N
    # One common gain stage for every stem and the mix: relative levels
    # (and therefore unity-gain reconstruction) are untouched; this is a
    # master trim, not per-file normalization.
    common_gain = 0.8
    for name, fn in STEMS:
        out = fn()
        for ch in out:
            for i in range(N):
                ch[i] *= common_gain
        for i in range(N):
            mix_l[i] += out[0][i]
            mix_r[i] += out[1][i]
        master = os.path.join(masters, name + ".wav")
        write_wav24(master, out[0], out[1])
        to_ogg(master, os.path.join(game, name + ".ogg"))
        peak = max(max(map(abs, out[0])), max(map(abs, out[1])))
        print(f"{name}: peak={peak:.3f} ({20 * math.log10(max(peak, 1e-9)):.1f} dBFS)")
    write_wav24(os.path.join(masters, "00_reference_mix.wav"), mix_l, mix_r)
    to_ogg(os.path.join(masters, "00_reference_mix.wav"),
           os.path.join(game, "reference_mix.ogg"))
    peak = max(max(map(abs, mix_l)), max(map(abs, mix_r)))
    print(f"reference mix: peak={peak:.3f} ({20 * math.log10(max(peak, 1e-9)):.1f} dBFS), "
          f"{N / SR:.4f}s @ {SR} Hz")
    if peak > 1.0:
        print("WARNING: reference mix clips at unity gain — lower authored gains")
        sys.exit(1)
    print("OK: 8 stems (no per-stem normalization) + reference mix")


if __name__ == "__main__":
    main()
