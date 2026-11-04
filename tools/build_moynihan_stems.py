#!/usr/bin/env python3
"""Synthesises the 8 Moynihan Train Hall stems for "Stay Until the Light Changes".

Deep progressive house blueprint: 122 BPM, 4/4, 8-bar loops in F minor with
relative-major warmth. Chord loop (2 bars each): Fm9 | Dmaj9 | Amaj9/E | E6/9.
Every voicing carries the same upper note, Ab5/G#5 (MIDI 80): the flat-3 of
Fm9, the #11 of Dmaj9, the major-7 of Amaj9 and the 3rd of E6/9.

Pure standard library (no numpy on this machine): WAVs are written with the
stdlib `wave` module and converted to Ogg Vorbis with ffmpeg when present.
If ffmpeg is missing the WAVs are left in place and reported as unresolved.

Usage: python3 tools/build_moynihan_stems.py [out_dir]
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
BPM = 122.0
BEAT = 60.0 / BPM
BARS = 8
N = int(round(BARS * 4 * BEAT * SR))  # 694033 samples = 15.7377 s
PEAK_LIMIT = 0.70  # about -3 dBFS
FADE = int(0.005 * SR)  # 5 ms loop-boundary fades

# (name, bass-root MIDI, voicing MIDI). Common upper note: 80 in every voicing.
CHORDS = [
    ("Fm9", 29, [53, 56, 60, 63, 67, 80]),
    ("Dmaj9", 38, [50, 54, 57, 61, 64, 80]),
    ("Amaj9/E", 40, [52, 57, 61, 64, 68, 80]),
    ("E6/9", 40, [52, 56, 59, 61, 66, 80]),
]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def buf():
    return [[0.0] * N, [0.0] * N]


def add(out, start_s, sig, pan=0.0, gain=1.0):
    """Mix mono `sig` into stereo `out` at start_s, wrapping around the loop."""
    lg = gain * math.cos((pan + 1) * math.pi / 4)
    rg = gain * math.sin((pan + 1) * math.pi / 4)
    i0 = int(round(start_s * SR)) % N
    left, right = out
    first = min(len(sig), N - i0)
    for j in range(first):
        s = sig[j]
        left[i0 + j] += s * lg
        right[i0 + j] += s * rg
    for j in range(first, len(sig)):
        s = sig[j]
        i = (i0 + j) % N
        left[i] += s * lg
        right[i] += s * rg


def tone(midi, dur, harmonics=(1.0,), attack=0.004, tau=None, gain=1.0,
         vib_hz=0.0, vib_cents=0.0):
    """Additive note with exponential decay (tau=None means sustain to dur)."""
    n = max(1, int(dur * SR))
    f0 = hz(midi)
    out = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = i / SR
        f = f0
        if vib_hz:
            f *= 2 ** ((vib_cents * min(1.0, max(0.0, (t - 0.15) / 0.3))
                        * math.sin(2 * math.pi * vib_hz * t)) / 1200)
        phase += 2 * math.pi * f / SR
        env = min(1.0, t / max(attack, 1e-4))
        if tau:
            env *= math.exp(-t / tau)
        else:  # gentle release in the last 60 ms so notes never click
            env *= min(1.0, max(0.0, (dur - t) / 0.06)) if t > dur - 0.06 else 1.0
        v = 0.0
        for k, a in enumerate(harmonics, 1):
            v += a * math.sin(k * phase)
        out[i] = v * env * gain
    return out


def noise_burst(rng, dur, tau, highpass=True):
    n = max(1, int(dur * SR))
    out = [0.0] * n
    prev = 0.0
    for i in range(n):
        t = i / SR
        w = rng.random() * 2 - 1
        v = (w - prev) if highpass else w  # one-zero highpass: bright, thin hats
        prev = w
        out[i] = v * math.exp(-t / tau) * min(1.0, t / 0.001)
    return out


def lowpass_seg(sig, fc):
    alpha = 1 - math.exp(-2 * math.pi * fc / SR)
    out = [0.0] * len(sig)
    y = 0.0
    for i, x in enumerate(sig):
        y += alpha * (x - y)
        out[i] = y
    return out


def biquad_bp(sig, freq, q):
    """RBJ bandpass (constant 0 dB peak gain), run over one short segment."""
    w0 = 2 * math.pi * freq / SR
    alpha = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1 + alpha, -2 * cw, 1 - alpha
    out = [0.0] * len(sig)
    x1 = x2 = y1 = y2 = 0.0
    for i, x in enumerate(sig):
        y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        out[i] = y
        x2, x1 = x1, x
        y2, y1 = y1, y
    return out


# ---------------------------------------------------------------- stems

def texture():
    """01: filtered concourse texture, low train rumble, distant metallic rattle."""
    rng = random.Random(101)
    out = buf()
    left, right = out
    alpha = 1 - math.exp(-2 * math.pi * 320 / SR)
    yl = yr = 0.0
    k_rumble = round(48 * N / SR)  # whole cycles per loop: wrap-safe
    k_hum = round(96 * N / SR)
    for i in range(N):
        ph = 2 * math.pi * i / N
        swell = 0.72 + 0.28 * math.sin(3 * ph + 0.7)  # 3 slow breaths per loop
        yl += alpha * ((rng.random() * 2 - 1) - yl)
        yr += alpha * ((rng.random() * 2 - 1) - yr)
        rumble = 0.55 * math.sin(k_rumble * ph) + 0.18 * math.sin(k_hum * ph + 1.1)
        train = 0.22 * math.sin(k_hum // 2 * ph) * (0.5 - 0.5 * math.cos(2 * ph))
        left[i] = yl * 1.5 * swell + (rumble + train) * 0.5
        right[i] = yr * 1.5 * swell + (rumble + train) * 0.5
    for _ in range(16):  # distant metallic rattles, never twice the same place
        start = rng.random() * BARS * 4 * BEAT
        ping = [0.0] * int(0.09 * SR)
        for i in range(len(ping)):
            t = i / SR
            ping[i] = (math.sin(2 * math.pi * 2830 * t) * 0.6
                       + math.sin(2 * math.pi * 3410 * t) * 0.4) * math.exp(-t * 55)
        add(out, start, ping, pan=rng.uniform(-0.7, 0.7), gain=0.16)
        add(out, start, noise_burst(rng, 0.05, 0.012), pan=rng.uniform(-0.7, 0.7), gain=0.10)
    return out


def kick():
    """02: tight four-on-the-floor kick + dry rim. No harmony."""
    rng = random.Random(102)
    out = buf()
    for beat in range(BARS * 4):
        n = int(0.30 * SR)
        hit = [0.0] * n
        phase = 0.0
        for i in range(n):
            t = i / SR
            f = 45 + 100 * math.exp(-t * 32)
            phase += 2 * math.pi * f / SR
            hit[i] = math.sin(phase) * math.exp(-t * 10.5) * min(1.0, t / 0.0012)
            hit[i] += (rng.random() * 2 - 1) * math.exp(-t * 320) * 0.22
        add(out, beat * BEAT, hit, gain=0.95)
        if beat % 2 == 1:  # dry rim on the off-beats, kept short and central
            rim = [0.0] * int(0.045 * SR)
            for i in range(len(rim)):
                t = i / SR
                rim[i] = (math.sin(2 * math.pi * 1720 * t) * 0.7
                          + (rng.random() * 2 - 1) * 0.3) * math.exp(-t * 130)
            add(out, beat * BEAT, rim, gain=0.30)
    return out


def hats():
    """03: swung closed hats + brighter shaker. Odd 16ths sit late."""
    rng = random.Random(103)
    out = buf()
    swing = 0.14 * (BEAT / 4)
    for step in range(BARS * 16):
        pos = step * BEAT / 4 + (swing if step % 2 else 0.0)
        accent = 1.0 if step % 4 == 2 else 0.55 if step % 2 == 0 else 0.38
        is_open = step % 32 == 30
        hat = noise_burst(rng, 0.28 if is_open else 0.05, 0.055 if is_open else 0.010)
        add(out, pos, hat, pan=-0.25 if step % 2 else 0.2, gain=0.5 * accent)
        shaker = noise_burst(rng, 0.07, 0.020)  # brighter, shorter, opposite side
        add(out, pos, shaker, pan=0.35 if step % 2 else -0.3,
            gain=0.20 * (1.0 if step % 4 == 0 else 0.6))
    return out


# Two-bar bass pattern: (beat in block, semitone offset, dur s, velocity).
# Short root just after the kick, a syncopated late-bar answer, an octave
# pickup into the next block; bar 2 answers differently. Silence does work.
BASS_PATTERN = [
    (0.5, 0, 0.30, 1.0), (2.5, 0, 0.20, 0.8), (3.5, 7, 0.24, 0.9),
    (4.5, 0, 0.30, 1.0), (6.25, 0, 0.18, 0.75), (7.5, 12, 0.16, 0.85),
]


def bass():
    """04: rounded bass following the 2-bar chord roots."""
    out = buf()
    for block, (_, root, _) in enumerate(CHORDS):
        base = block * 8 * BEAT
        for beat, semi, dur, vel in BASS_PATTERN:
            sig = tone(root + semi, dur, harmonics=(1.0, 0.38, 0.14),
                       attack=0.004, tau=0.17, gain=vel)
            add(out, base + beat * BEAT, lowpass_seg(sig, 900))
    return out


def perc():
    """05: hand-percussion conversation around one syncopated phrase per bar."""
    rng = random.Random(105)
    out = buf()

    def clap(gain):
        sig = [0.0] * int(0.16 * SR)
        for i in range(len(sig)):
            t = i / SR
            burst = sum(math.exp(-((t - c) ** 2) / (2 * 0.004 ** 2))
                        for c in (0.0, 0.011, 0.023))
            sig[i] = (rng.random() * 2 - 1) * (burst + 0.35 * math.exp(-t * 45)) * gain
        return lowpass_seg(sig, 3800)

    def block_hit(midi, gain):
        sig = tone(midi, 0.09, harmonics=(1.0, 0.2), attack=0.002, tau=0.028, gain=gain)
        return sig

    for bar in range(BARS):
        base = bar * 4 * BEAT
        add(out, base + 1.0 * BEAT, clap(0.55), pan=-0.1)          # clap on 2
        add(out, base + 1.75 * BEAT, block_hit(76, 0.5), pan=0.3)  # answer in the gap
        add(out, base + 2.5 * BEAT, block_hit(74, 0.42), pan=0.35)
        add(out, base + 3.0 * BEAT, clap(0.5), pan=-0.1)           # clap on 4
        add(out, base + 3.75 * BEAT, block_hit(79, 0.36), pan=-0.35)
    metal = [0.0] * int(0.22 * SR)  # one small metallic hit per 2-bar block
    for i in range(len(metal)):
        t = i / SR
        metal[i] = (math.sin(2 * math.pi * 2390 * t)
                    + 0.6 * math.sin(2 * math.pi * 3170 * t)
                    + 0.35 * math.sin(2 * math.pi * 4210 * t)) * math.exp(-t * 26)
    for block in range(4):
        add(out, block * 8 * BEAT + 7.5 * BEAT, metal, pan=0.45, gain=0.22)
    return out


def chords():
    """06: warm electric-piano chord stabs, sparse and short, common top note."""
    out = buf()
    stab_beats = (0.0, 2.5, 4.0, 6.75)
    for block, (_, _, voicing) in enumerate(CHORDS):
        base = block * 8 * BEAT
        for beat in stab_beats:
            for j, midi in enumerate(voicing):
                gain = 0.34 if midi == 80 else 0.5  # keep the common top note soft
                sig = tone(midi, 0.55, harmonics=(1.0, 0.5, 0.22, 0.09),
                           attack=0.004, tau=0.30, gain=gain)
                add(out, base + beat * BEAT, sig,
                    pan=-0.45 + 0.9 * j / max(1, len(voicing) - 1))
    return out


# Hook: (beat, MIDI, dur in beats). Small upward reach, falling answer,
# four-bar phrase; the repeat lands on the common upper note.
HOOK = [
    (0.0, 65, 1.5), (1.5, 68, 1.0), (2.5, 72, 2.0), (4.5, 70, 1.0), (5.5, 68, 2.5),
    (8.0, 69, 1.5), (9.5, 73, 1.0), (10.5, 76, 2.0), (12.5, 74, 1.0), (13.5, 73, 2.5),
]


def hook():
    """07: luminous lead, 4-bar motif phrase played twice."""
    out = buf()
    for rep in range(2):
        for beat, midi, dur in HOOK:
            if rep == 1 and beat == 13.5:
                midi, dur = 80, 2.5  # second ending reaches the common note
            sig = tone(midi, dur * BEAT, harmonics=(1.0, 0.34, 0.16, 0.06),
                       attack=0.02, vib_hz=5.4, vib_cents=9, gain=0.62)
            add(out, (rep * 16 + beat) * BEAT, sig, pan=0.08)
    return out


# Vox syllables of "stay un-til the light chang-es": (formant1, formant2).
VOX_SYL = [(730, 1090), (640, 1190), (440, 1800), (500, 1500),
           (730, 1090), (660, 1200), (440, 1800)]
VOX_BEATS = (0.5, 1.25, 2.0, 2.75, 3.5, 4.5, 5.25)
VOX_PITCH = (65, 63, 65, 68, 67, 65, 63)


def vox():
    """08: formant-filtered synth syllables chopping the title phrase.

    Abstract syllable motif, not intelligible speech synthesis: a harmonic
    source at sung pitch through two resonant bandpass formants per vowel.
    """
    out = buf()
    for block in range(4):
        base = block * 8 * BEAT
        for (f1, f2), beat, midi in zip(VOX_SYL, VOX_BEATS, VOX_PITCH):
            dur = 0.24
            n = int(dur * SR)
            f0 = hz(midi)
            src = [0.0] * n
            phase = 0.0
            for i in range(n):
                t = i / SR
                phase += 2 * math.pi * f0 * (1 + 0.003 * math.sin(2 * math.pi * 5 * t)) / SR
                env = min(1.0, t / 0.018) * math.exp(-t * 5.5)
                v = 0.0
                for k in range(1, 9):
                    v += math.sin(k * phase) / k
                src[i] = v * env
            sig = [a + 0.7 * b for a, b in zip(biquad_bp(src, f1, 7),
                                               biquad_bp(src, f2, 9))]
            add(out, base + beat * BEAT, sig, pan=0.05, gain=0.8)
    return out


# (file, generator, target RMS dBFS)
STEMS = [
    ("01_texture", texture, -27.0),
    ("02_kick", kick, -20.0),
    ("03_hats", hats, -32.0),
    ("04_bass", bass, -20.0),
    ("05_perc", perc, -27.0),
    ("06_chords", chords, -23.0),
    ("07_hook", hook, -21.0),
    ("08_vox", vox, -25.0),
]


def finish(out, rms_db):
    """Scale to target RMS, soft-limit peaks, apply 5 ms boundary fades."""
    total = sum(v * v for ch in out for v in ch)
    rms = math.sqrt(total / (2 * N)) or 1.0
    scale = 10 ** (rms_db / 20) / rms
    peak = 0.0
    for ch in out:
        for i in range(N):
            ch[i] *= scale
            peak = max(peak, abs(ch[i]))
    if peak > PEAK_LIMIT:
        for ch in out:
            for i in range(N):
                ch[i] = math.tanh(ch[i] / PEAK_LIMIT) * PEAK_LIMIT
        peak = PEAK_LIMIT
    for ch in out:
        for i in range(FADE):
            r = i / FADE
            ch[i] *= r
            ch[N - 1 - i] *= r
    return peak


def write_wav(path, out):
    pcm = bytearray()
    pack = struct.Struct("<hh").pack
    for i in range(N):
        pcm += pack(int(max(-1, min(1, out[0][i])) * 32767),
                    int(max(-1, min(1, out[1][i])) * 32767))
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(bytes(pcm))


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                        "stream=sample_rate", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1", "--", path],
                       capture_output=True, text=True, check=True)
    vals = dict(line.split("=", 1) for line in r.stdout.strip().splitlines() if "=" in line)
    return float(vals["duration"]), int(vals["sample_rate"])


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..",
        "app", "src", "main", "assets", "music", "moynihan")
    out_dir = os.path.normpath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    have_ffmpeg = subprocess.run(["ffmpeg", "-version"], capture_output=True).returncode == 0
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, fn, rms_db in STEMS:
            out = fn()
            peak = finish(out, rms_db)
            wav = os.path.join(tmp, name + ".wav")
            write_wav(wav, out)
            dest = os.path.join(out_dir, name + ".ogg")
            if have_ffmpeg:
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                                "-c:a", "libvorbis", "-q:a", "5", dest], check=True)
                dur, sr = probe(dest)
            else:  # leave the WAV next to the expected .ogg location
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
