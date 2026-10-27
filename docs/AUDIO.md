# Audio

## How the soundtrack works in the game

The track is eight stems that loop together, sample-locked, for the whole run. Nothing is
"started" when you collect a fragment; everything is already playing and the game only moves
faders.

* **Collected** stems play inside your head, stereo, full level.
* **The one you're hunting** plays from the signal's real-world bearing through the binaural
  renderer. Far away it's low-passed, sample-and-hold crushed and buried under radio static;
  as you approach it clears up and gets louder. A sonar ping from the same direction speeds up
  from every ~4 s to every ~0.6 s.
* **When you win** the fragment, it glides from the world into your head over one bar, starting
  on the next bar line, so it always lands on the grid.
* **When a character talks**, the music ducks. **When the mic is open**, the music ducks harder
  and is squeezed into a 300 Hz–3.4 kHz walkie-talkie band, with a squelch chirp on and off.
  This is deliberate: opening the glasses mic over the Bluetooth call profile usually drops the
  whole link to mono call quality, and the comms effect turns that into part of the fiction.
* The finale brings everything in, in the head.

## Replacing the placeholder stems

The repo ships synthesized placeholders so the game is playable today
(`tools/make_placeholder_stems.py`). To use your own:

1. Export **eight stems of exactly the same length**, one per fragment, from the same session:
   * 48 kHz (44.1 kHz works; it's resampled), 16- or 24-bit, stereo or mono.
   * A whole number of bars that loops cleanly: no reverb tails cut at the end, and print the
     tail onto the start if you need to (the loop point is the end of the file).
   * Keep the length under about a minute: stems are held in memory, uncompressed.
2. Encode to OGG Vorbis (`ffmpeg -i stem.wav -c:a libvorbis -q:a 6 stem.ogg`), or use FLAC or
   WAV if you'd rather keep them lossless. Put them in `app/src/main/assets/music/`.
3. Update the `music` block of `app/src/main/assets/mission/ghostsignal.json`: `bpm`,
   `beatsPerBar`, `loopBars` and each stem's `file`, `name` and `gainDb`. The engine trims or pads
   every stem to exactly `loopBars` bars, so encoder padding can never pull them out of sync.
4. Order matters for the story: signal *n* unlocks the stem named in its `stem` field. The
   placeholder order is kick, hats, bass, perc, pad, arp, lead, vox.

Mixing tips for the beacon effect:

* Each stem is heard **alone** at first (a mono, crushed version from a direction), then on top of
  the ones already collected. A stem that's interesting solo makes a better beacon.
* Low-frequency parts (kick, bass) localize poorly. They work better early, when the runner is
  learning the mechanic and distance matters more than direction.
* Leave headroom: eight stems sum, then a soft clipper on the master. Peaks around −9 dBFS per
  stem are a good start; fine-tune with `gainDb`.

## HUSH's theme

`music/hush/hush_theme.ogg` is the sound of the city being listened to: minimal industrial
horror from HUSH's side. A building-sized air handler that breathes, a mains transformer hum, a
clean beating test tone, a struck steel beam ringing out every four bars in a huge concrete
space, swells sucked backwards into silence, a servo turning toward you, and faint telemetry
ticks. It is one 16-bar loop at 88 BPM (about 43.6 s) and is kept quiet and sparse on purpose.

The game plays it, inside the head, whenever HUSH holds the frequency: under the prologue that
opens every run, and around The Fog, where it grows as the runner closes in and pushes the
pirate music down (`antagonist.duck` in the mission file) until they comply or jam. Regenerate it
with `python3 tools/make_hush_theme.py`, or replace the file with your own loop; any length works,
and anything that rings should be printed back onto the start so it loops cleanly.

## Dead Channel: the soundtrack you unlock

Finishing the mission once unlocks a second soundtrack, **Dead Channel**: Vesper's night mix of
the Last Eight Bars, slower (88 BPM, D minor) and colder, with detuned pads, a warbly climbing
arpeggio, crushed ticks, a pulsing undertow and a ghost choir on tape that wanders a little. The
original stays the default.

How the reward works:

* The phone counts finished runs (a run finishes when the outro ends). Starting a new run never
  resets that count.
* On the run that unlocks it, Vesper and the SmartSpecs announce it after the outro, and the screen
  shows **★ UNLOCKED · Dead Channel**. That line is pre-rendered with the rest of the script.
* The next run plays Dead Channel automatically. The start screen's **Soundtrack** section switches
  between the two at any time; a locked one shows how many runs it needs.
* On a shared event phone, **Settings → Testing → Re-lock rewards** puts it back to a first-time
  runner's state before the next person.

It fills the same eight slots as the original (kick, hats, bass, perc, pad, arp, lead, vox), so
every signal still unlocks the part its lines talk about, and Mari's rhythm challenge clicks at
the soundtrack's own tempo.

### The violin slot

`music/dead_channel/07_lead.ogg` ("The Voice") is meant for violin. What ships is a bowed-synth
stand-in in violin range, and the pad and choir are cut around 1.3 kHz to leave the violin room.
To drop in a real take:

1. Record against the other seven stems at **88 BPM, 4/4, D minor**, for exactly **8 bars**
   (21.818 s, 1,047,273 samples at 48 kHz). The progression is two bars each of
   Dm9 · B♭maj9 · Gm9 · A7sus4 resolving to A7 halfway through the last pair.
2. Print any reverb tail back onto the start of the file so it loops cleanly, as with any stem.
3. Encode it to `app/src/main/assets/music/dead_channel/07_lead.ogg` (or point the `lead` stem's
   `file` at a WAV or FLAC) and adjust its `gainDb` in the mission file. Remember it is heard alone
   first, as the beacon for The Voice.

### Regenerating or replacing it

`python3 tools/make_dead_channel_stems.py` rebuilds all eight stems (numpy and ffmpeg). Every
reverb, delay and tape wobble in it wraps around the loop point, so the files loop without a seam.
To replace the whole set with your own recordings, follow the same rules as for the original
stems; the manifest is the `soundtracks` entry with id `dead-channel` in the mission file. Add
more soundtracks the same way: each needs a stem for every slot (the validator checks), and
`unlockAfterCompletions` sets how many finished runs unlock it (0 = from the start).

## Deploying soundtracks without an app update

The base game is meant to have three soundtracks: the original, which ships with the app, plus
two deployed from the backend's admin page (`/admin`). Dead Channel stays the bonus you earn by
finishing a run. To deploy one:

1. Admin page → **Soundtracks → New soundtrack**. Give it a title, its BPM, beats per bar and loop
   length in bars.
2. Upload one stem per signal: Ogg Vorbis, Opus, FLAC, or 16/24-bit WAV, mono or stereo, any
   common sample rate (the phone resamples to 48 kHz). Each row says whether the file loops on the
   beat; a stem that's too long is cut at the loop point and one that's too short is padded with
   silence, so fix those before publishing. Ogg keeps downloads small (about 0.5 MB per stem against
   4 MB for WAV).
3. Choose **Base game** (every runner, from the first run), **Bonus** (unlocks after a number of
   finished runs) or **Premium** (premium players only; see Accounts and premium in the README),
   then **Publish**. **Save as draft** keeps it off the phones until it's ready.

Phones pick it up the next time GhostSignal opens or setup is shown, download each stem once, check
it against its SHA-256, and only offer the soundtrack once every stem is on the phone. Taking a
soundtrack down (Delete) removes it from phones at their next sync; a runner who had it selected
goes back to the original. Stems are stored on the server by checksum under `DATA_DIR/blobs`.

## Spatial renderers

### Built-in binaural (default)

`app/src/main/kotlin/com/ghostsignal/app/audio/BinauralMixer.kt`. A single 48 kHz float
AudioTrack fed by a real-time thread in 10 ms blocks. Each spatial source goes through a
spherical-head model: Woodworth interaural time difference (up to ~0.66 ms), interaural level
difference, a far-ear head-shadow low-pass, and a duller, quieter colour for sources behind you.
It isn't a measured HRTF, but over open-ear speakers it reads clearly as left, right, front and
back, and it costs little CPU on a budget phone. Head heading is applied per block, straight
from the IMU stream, so world-anchored sources respond to head turns as fast as the IMU data and
the Bluetooth link allow.

### FMOD + Resonance Audio (optional)

`FmodAudioEngine.kt` and `app/src/main/cpp/fmod_bridge.cpp`. Each stem plays on two synchronized
FMOD channels, one in the head and one spatial, cross-faded by the game. Spatial channels go
through the **Resonance Audio** source and listener plugins when they load (true HRTF), and
otherwise through FMOD's built-in 3D panner. Sound effects, the sonar ping, static and rhythm
clicks still come from the built-in mixer on a second stream.

To enable it:

1. Download the **FMOD Engine** for Android from fmod.com (free account; check the licence fits
   your project).
2. Unpack it so that `third_party/fmod/api/core/inc/fmod.hpp` and
   `third_party/fmod/api/core/lib/<abi>/libfmod.so` exist. (`third_party/fmod` is git-ignored;
   don't commit the SDK.)
3. For binaural rendering, copy the Resonance Audio FMOD plugin for Android
   (`libresonanceaudio.so`, one per ABI) to `third_party/fmod/plugins/<abi>/`. Without it, FMOD
   uses plain 3D panning.
4. Build. Gradle detects the SDK, compiles the JNI bridge with CMake (Android NDK required) and
   sets `BuildConfig.FMOD_ENABLED`. In the app, Settings → Audio engine → *FMOD* (or *Auto*).

The FMOD bridge was written against the FMOD Core 2.x API but couldn't be compiled here because
the SDK requires a licensed download. Expect a short compile-fix pass the first time you build it,
and compare it with the built-in renderer in check 5 of the field test before choosing.
