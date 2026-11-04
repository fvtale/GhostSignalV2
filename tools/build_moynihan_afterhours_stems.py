#!/usr/bin/env python3
"""Synthesises the After-Hours Cut stems: the unlockable alternate mix of
"Stay Until the Light Changes" for the Moynihan Train Hall mission.

Darker and moodier than the main cut, with alternate beats: the kick goes
broken instead of four-on-the-floor, chords drop a register and lose the
bright common top note, the hook fragments and sinks an octave, and the
vocal motif returns lower and sparser. Same 122 BPM / 8-bar loop shape
and the same F-minor chord roots, so the engine swaps it in cleanly.

Reuses the synth toolkit from build_moynihan_stems.py (pure stdlib).

Usage: python3 tools/build_moynihan_afterhours_stems.py [out_dir]
"""

import importlib.util
import math
import os
import random
import subprocess
import sys
import tempfile

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "moynihan_stems", os.path.join(_here, "build_moynihan_stems.py"))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)

SR, BEAT, BARS, N = base.SR, base.BEAT, base.BARS, base.N
buf, add, tone = base.buf, base.add, base.tone
noise_burst, lowpass_seg, biquad_bp = base.noise_burst, base.lowpass_seg, base.biquad_bp
CHORDS = base.CHORDS


def texture_dark():
    """01: night concourse — darker filter, deeper rumble, rare rattles."""
    rng = random.Random(201)
    out = buf()
    left, right = out
    alpha = 1 - math.exp(-2 * math.pi * 170 / SR)
    yl = yr = 0.0
    k_rumble = round(48 * N / SR)
    k_hum = round(96 * N / SR)
    for i in range(N):
        ph = 2 * math.pi * i / N
        swell = 0.8 + 0.2 * math.sin(2 * ph + 0.7)  # 2 slow breaths, not 3
        yl += alpha * ((rng.random() * 2 - 1) - yl)
        yr += alpha * ((rng.random() * 2 - 1) - yr)
        rumble = 0.62 * math.sin(k_rumble * ph) + 0.2 * math.sin(k_hum * ph + 1.1)
        train = 0.26 * math.sin(k_hum // 2 * ph) * (0.5 - 0.5 * math.cos(2 * ph))
        left[i] = yl * 1.4 * swell + (rumble + train) * 0.55
        right[i] = yr * 1.4 * swell + (rumble + train) * 0.55
    for _ in range(7):  # fewer, further away
        start = rng.random() * BARS * 4 * BEAT
        ping = [0.0] * int(0.12 * SR)
        for i in range(len(ping)):
            t = i / SR
            ping[i] = (math.sin(2 * math.pi * 2140 * t) * 0.6
                       + math.sin(2 * math.pi * 2680 * t) * 0.4) * math.exp(-t * 44)
        add(out, start, ping, pan=rng.uniform(-0.7, 0.7), gain=0.11)
    return out


def kick_dark():
    """02: alternate beat — broken kick, ghost on odd bars, no four-floor."""
    rng = random.Random(202)
    out = buf()

    def hit(gain):
        n = int(0.30 * SR)
        sig = [0.0] * n
        phase = 0.0
        for i in range(n):
            t = i / SR
            f = 42 + 92 * math.exp(-t * 32)
            phase += 2 * math.pi * f / SR
            sig[i] = math.sin(phase) * math.exp(-t * 10.5) * min(1.0, t / 0.0012)
            sig[i] += (rng.random() * 2 - 1) * math.exp(-t * 320) * 0.18
        return [s * gain for s in sig]

    for bar in range(BARS):
        base_b = bar * 4
        for beat, gain in ((0.0, 0.95), (2.0, 0.9), (3.5, 0.8)):
            add(out, (base_b + beat) * BEAT, hit(gain))
        if bar % 2 == 1:  # ghost kick breathes on odd bars only
            add(out, (base_b + 1.0) * BEAT, hit(0.45))
        if bar % 4 == 3:  # dry rim, once per phrase, late
            rim = [0.0] * int(0.045 * SR)
            for i in range(len(rim)):
                t = i / SR
                rim[i] = (math.sin(2 * math.pi * 1490 * t) * 0.7
                          + (rng.random() * 2 - 1) * 0.3) * math.exp(-t * 130)
            add(out, (base_b + 3.75) * BEAT, rim, gain=0.26)
    return out


def hats_dark():
    """03: same shuffle, heavier swing, sparser and duller."""
    rng = random.Random(203)
    out = buf()
    swing = 0.22 * (BEAT / 4)
    for step in range(BARS * 16):
        if step % 4 == 1:
            continue  # holes where the main cut had hats
        pos = step * BEAT / 4 + (swing if step % 2 else 0.0)
        accent = 1.0 if step % 4 == 2 else 0.5 if step % 2 == 0 else 0.34
        is_open = step % 32 == 14
        hat = noise_burst(rng, 0.30 if is_open else 0.05, 0.06 if is_open else 0.011)
        add(out, pos, hat, pan=-0.25 if step % 2 else 0.2, gain=0.42 * accent)
        if step % 2 == 0:
            shaker = noise_burst(rng, 0.07, 0.022)
            add(out, pos, shaker, pan=0.3 if step % 4 else -0.28, gain=0.15)
    return out


# Sparser than the main pattern; the pickup drops an octave instead of rising.
BASS_PATTERN_DARK = [
    (0.5, 0, 0.34, 1.0), (3.5, 0, 0.22, 0.8),
    (4.5, 0, 0.34, 1.0), (6.75, 0, 0.18, 0.7), (7.5, -12, 0.18, 0.8),
]


def bass_dark():
    """04: deeper sub, more silence, answers fall instead of climb."""
    out = buf()
    for block, (_, root, _) in enumerate(CHORDS):
        base_b = block * 8 * BEAT
        for beat, semi, dur, vel in BASS_PATTERN_DARK:
            sig = tone(root + semi, dur, harmonics=(1.0, 0.25, 0.08),
                       attack=0.005, tau=0.22, gain=vel)
            add(out, base_b + beat * BEAT, lowpass_seg(sig, 650))
    return out


def perc_dark():
    """05: the conversation at lower pitch, answers further apart."""
    rng = random.Random(205)
    out = buf()

    def clap(gain):
        sig = [0.0] * int(0.16 * SR)
        for i in range(len(sig)):
            t = i / SR
            burst = sum(math.exp(-((t - c) ** 2) / (2 * 0.004 ** 2))
                        for c in (0.0, 0.011, 0.023))
            sig[i] = (rng.random() * 2 - 1) * (burst + 0.35 * math.exp(-t * 45)) * gain
        return lowpass_seg(sig, 2400)

    for bar in range(BARS):
        base_b = bar * 4 * BEAT
        if bar % 2 == 0:
            add(out, base_b + 1.0 * BEAT, clap(0.45), pan=-0.1)
        add(out, base_b + 2.5 * BEAT,
            tone(64, 0.09, harmonics=(1.0, 0.2), attack=0.002, tau=0.03, gain=0.42), pan=0.32)
        if bar % 2 == 1:
            add(out, base_b + 3.75 * BEAT,
                tone(62, 0.09, harmonics=(1.0, 0.2), attack=0.002, tau=0.03, gain=0.36), pan=-0.32)
    metal = [0.0] * int(0.26 * SR)
    for i in range(len(metal)):
        t = i / SR
        metal[i] = (math.sin(2 * math.pi * 1710 * t)
                    + 0.6 * math.sin(2 * math.pi * 2290 * t)) * math.exp(-t * 22)
    for block in range(4):
        add(out, block * 8 * BEAT + 7.5 * BEAT, metal, pan=0.4, gain=0.18)
    return out


def chords_dark():
    """06: voicings drop a register, lose the bright top note, ring longer."""
    out = buf()
    for block, (_, _, voicing) in enumerate(CHORDS):
        base_b = block * 8 * BEAT
        low_voicing = [m - 12 for m in voicing if m < 80]
        for beat in (0.0, 3.5):
            for j, midi in enumerate(low_voicing):
                sig = tone(midi, 1.15, harmonics=(1.0, 0.35, 0.12),
                           attack=0.006, tau=0.55, gain=0.46)
                add(out, base_b + beat * BEAT, sig,
                    pan=-0.35 + 0.7 * j / max(1, len(low_voicing) - 1))
    return out


def hook_dark():
    """07: the motif fragments and sinks an octave; second pass trails off."""
    out = buf()
    fragments = [(0, [(0.0, 65, 1.5), (2.5, 72, 2.0), (5.5, 68, 2.5)]),
                 (1, [(0.0, 65, 1.5), (2.5, 72, 2.0)])]
    for rep, notes in fragments:
        for beat, midi, dur in notes:
            sig = tone(midi - 12, dur * BEAT, harmonics=(1.0, 0.25, 0.10),
                       attack=0.05, vib_hz=6.2, vib_cents=14, gain=0.58)
            add(out, (rep * 16 + beat) * BEAT, sig, pan=0.05)
    return out


def vox_dark():
    """08: the phrase returns lower, slower, and only in pieces."""
    out = buf()
    syl = [(base.VOX_SYL[i][0] * 0.82, base.VOX_SYL[i][1] * 0.82) for i in (0, 2, 4, 6)]
    for block in range(4):
        base_b = block * 8 * BEAT
        for (f1, f2), beat, midi in zip(syl, (0.5, 2.0, 3.5, 5.25), (60, 58, 60, 55)):
            dur = 0.34
            n = int(dur * SR)
            f0 = base.hz(midi)
            src = [0.0] * n
            phase = 0.0
            for i in range(n):
                t = i / SR
                phase += 2 * math.pi * f0 * (1 + 0.004 * math.sin(2 * math.pi * 4.5 * t)) / SR
                env = min(1.0, t / 0.03) * math.exp(-t * 4.2)
                v = 0.0
                for k in range(1, 9):
                    v += math.sin(k * phase) / k
                src[i] = v * env
            sig = [a + 0.7 * b for a, b in zip(biquad_bp(src, f1, 7),
                                               biquad_bp(src, f2, 9))]
            add(out, base_b + beat * BEAT, sig, pan=0.05, gain=0.75)
    return out


# (file, generator, target RMS dBFS) — same filenames as the main cut.
STEMS = [
    ("01_texture", texture_dark, -28.0),
    ("02_kick", kick_dark, -20.0),
    ("03_hats", hats_dark, -34.0),
    ("04_bass", bass_dark, -19.0),
    ("05_perc", perc_dark, -29.0),
    ("06_chords", chords_dark, -24.0),
    ("07_hook", hook_dark, -23.0),
    ("08_vox", vox_dark, -27.0),
]


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        _here, "..", "app", "src", "main", "assets", "music", "moynihan_afterhours")
    out_dir = os.path.normpath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    have_ffmpeg = subprocess.run(["ffmpeg", "-version"], capture_output=True).returncode == 0
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, fn, rms_db in STEMS:
            out = fn()
            peak = base.finish(out, rms_db)
            wav = os.path.join(tmp, name + ".wav")
            base.write_wav(wav, out)
            dest = os.path.join(out_dir, name + ".ogg")
            if have_ffmpeg:
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                                "-c:a", "libvorbis", "-q:a", "5", dest], check=True)
                dur, sr = base.probe(dest)
            else:
                dest = os.path.join(out_dir, name + ".wav")
                os.replace(wav, dest)
                dur, sr = N / SR, SR
            results.append((name, dur, sr, peak))
            print(f"{name}: duration={dur:.4f}s sr={sr} peak={peak:.3f} "
                  f"({20 * math.log10(max(peak, 1e-9)):.1f} dBFS) -> {dest}")
    durations = [d for _, d, _, _ in results]
    assert max(durations) - min(durations) <= 0.05, durations
    assert all(s == SR for _, _, s, _ in results)
    print(f"OK: {len(results)} stems, durations within "
          f"{max(durations) - min(durations):.4f}s, all {SR} Hz")
    if not have_ffmpeg:
        print("UNRESOLVED: ffmpeg not found; WAV files written instead of .ogg")


if __name__ == "__main__":
    main()
