// JNI bridge between FmodAudioEngine.kt and FMOD Core.
//
// Each music stem plays on two synchronised channels: a 2D "in-head" channel and a spatial
// channel. The Kotlin side cross-fades between them, which is how a collected fragment glides
// from its place in the world into the runner's head. Spatial channels are rendered binaurally by
// the Resonance Audio plugin when it loads, otherwise by FMOD's built-in 3D panner.

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#include "fmod.hpp"
#include "fmod_errors.h"

#define LOG_TAG "GhostSignalFmod"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

bool ok(FMOD_RESULT r, const char* what) {
  if (r != FMOD_OK) LOGW("%s: %s", what, FMOD_ErrorString(r));
  return r == FMOD_OK;
}

struct Spatial {
  FMOD::Channel* channel = nullptr;
  FMOD::DSP* source = nullptr;  // Resonance source, if available
};

struct Stem {
  FMOD::Sound* sound = nullptr;
  FMOD::Channel* dry = nullptr;
  Spatial wet;
  float baseGain = 1.0f;
};

struct Engine {
  FMOD::System* system = nullptr;
  FMOD::ChannelGroup* master = nullptr;
  FMOD::ChannelGroup* music = nullptr;
  FMOD::ChannelGroup* voices = nullptr;
  FMOD::DSP* listener = nullptr;
  FMOD::DSP* lofi = nullptr;
  unsigned int sourcePlugin = 0;
  std::vector<Stem> stems;
  FMOD::Sound* voiceSound = nullptr;
  Spatial voice;
  std::mutex lock;
};

Engine* E(jlong h) { return reinterpret_cast<Engine*>(h); }

// Finds the parameter that carries 3D attributes on a spatialiser DSP.
int attributesParam(FMOD::DSP* dsp) {
  int count = 0;
  if (dsp->getNumParameters(&count) != FMOD_OK) return -1;
  for (int i = 0; i < count; ++i) {
    FMOD_DSP_PARAMETER_DESC* desc = nullptr;
    if (dsp->getParameterInfo(i, &desc) != FMOD_OK || desc == nullptr) continue;
    if (desc->type == FMOD_DSP_PARAMETER_TYPE_DATA &&
        desc->datadesc.datatype == FMOD_DSP_PARAMETER_DATA_TYPE_3DATTRIBUTES) {
      return i;
    }
  }
  return -1;
}

void place(Engine* e, Spatial& s, float x, float y, float z) {
  if (s.channel == nullptr) return;
  FMOD_VECTOR pos = {x, y, z};
  FMOD_VECTOR zero = {0.0f, 0.0f, 0.0f};
  if (s.source != nullptr) {
    int idx = attributesParam(s.source);
    if (idx >= 0) {
      FMOD_DSP_PARAMETER_3DATTRIBUTES attrs;
      std::memset(&attrs, 0, sizeof(attrs));
      attrs.relative.position = pos;
      attrs.relative.forward = {0.0f, 0.0f, 1.0f};
      attrs.relative.up = {0.0f, 1.0f, 0.0f};
      attrs.absolute = attrs.relative;
      s.source->setParameterData(idx, &attrs, sizeof(attrs));
    }
  } else {
    s.channel->set3DAttributes(&pos, &zero);
  }
}

// Makes a channel spatial: Resonance source DSP if we have the plugin, else FMOD 3D.
void makeSpatial(Engine* e, Spatial& s) {
  if (e->sourcePlugin != 0 && ok(e->system->createDSPByPlugin(e->sourcePlugin, &s.source), "create source")) {
    s.channel->set3DLevel(0.0f);  // Resonance does the panning
    s.channel->addDSP(FMOD_CHANNELCONTROL_DSP_HEAD, s.source);
  } else {
    s.source = nullptr;
    s.channel->set3DLevel(1.0f);
  }
}

void releaseSpatial(Spatial& s) {
  if (s.channel != nullptr) s.channel->stop();
  if (s.source != nullptr) s.source->release();
  s.channel = nullptr;
  s.source = nullptr;
}

FMOD::Sound* soundFromPcm(Engine* e, JNIEnv* env, jshortArray pcm, int channels, int rate, bool loop) {
  jsize n = env->GetArrayLength(pcm);
  jshort* data = env->GetShortArrayElements(pcm, nullptr);
  FMOD_CREATESOUNDEXINFO ex;
  std::memset(&ex, 0, sizeof(ex));
  ex.cbsize = sizeof(ex);
  ex.length = static_cast<unsigned int>(n) * sizeof(jshort);
  ex.numchannels = channels;
  ex.defaultfrequency = rate;
  ex.format = FMOD_SOUND_FORMAT_PCM16;
  FMOD_MODE mode = FMOD_OPENMEMORY | FMOD_OPENRAW | FMOD_CREATESAMPLE | FMOD_3D | FMOD_3D_HEADRELATIVE |
                   (loop ? FMOD_LOOP_NORMAL : FMOD_LOOP_OFF);
  FMOD::Sound* sound = nullptr;
  ok(e->system->createSound(reinterpret_cast<const char*>(data), mode, &ex, &sound), "createSound");
  env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
  return sound;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeInit(JNIEnv* env, jclass, jint sampleRate,
                                                                             jstring plugin) {
  auto* e = new Engine();
  if (!ok(FMOD::System_Create(&e->system), "System_Create")) {
    delete e;
    return 0;
  }
  e->system->setSoftwareFormat(sampleRate, FMOD_SPEAKERMODE_STEREO, 0);
  if (!ok(e->system->init(64, FMOD_INIT_NORMAL, nullptr), "init")) {
    e->system->release();
    delete e;
    return 0;
  }
  e->system->set3DSettings(1.0f, 1.0f, 1.0f);
  FMOD_VECTOR origin = {0.0f, 0.0f, 0.0f};
  FMOD_VECTOR forward = {0.0f, 0.0f, 1.0f};
  FMOD_VECTOR up = {0.0f, 1.0f, 0.0f};
  e->system->set3DListenerAttributes(0, &origin, &origin, &forward, &up);
  e->system->getMasterChannelGroup(&e->master);
  e->system->createChannelGroup("music", &e->music);
  e->system->createChannelGroup("voices", &e->voices);

  // Resonance Audio ships as a plugin bundle with Listener, Soundfield and Source DSPs.
  const char* name = env->GetStringUTFChars(plugin, nullptr);
  unsigned int bundle = 0;
  if (e->system->loadPlugin(name, &bundle) == FMOD_OK) {
    int count = 0;
    e->system->getNumNestedPlugins(bundle, &count);
    for (int i = 0; i < count; ++i) {
      unsigned int nested = 0;
      const FMOD_DSP_DESCRIPTION* desc = nullptr;
      if (e->system->getNestedPlugin(bundle, i, &nested) != FMOD_OK) continue;
      if (e->system->getDSPInfoByPlugin(nested, &desc) != FMOD_OK || desc == nullptr) continue;
      if (std::strstr(desc->name, "Listener") != nullptr) {
        if (ok(e->system->createDSPByPlugin(nested, &e->listener), "create listener")) {
          e->master->addDSP(FMOD_CHANNELCONTROL_DSP_HEAD, e->listener);
        }
      } else if (std::strstr(desc->name, "Source") != nullptr) {
        e->sourcePlugin = nested;
      }
    }
    LOGI("Resonance Audio %s", e->listener != nullptr && e->sourcePlugin != 0 ? "ready" : "incomplete");
    if (e->listener == nullptr) e->sourcePlugin = 0;
  } else {
    LOGW("Resonance Audio plugin %s not found; using FMOD 3D panning", name);
  }
  env->ReleaseStringUTFChars(plugin, name);

  // Walkie-talkie band for the music bus while the mic is open.
  if (ok(e->system->createDSPByType(FMOD_DSP_TYPE_MULTIBAND_EQ, &e->lofi), "create eq")) {
    e->lofi->setParameterInt(FMOD_DSP_MULTIBAND_EQ_A_FILTER, FMOD_DSP_MULTIBAND_EQ_FILTER_HIGHPASS_12DB);
    e->lofi->setParameterFloat(FMOD_DSP_MULTIBAND_EQ_A_FREQUENCY, 320.0f);
    e->lofi->setParameterInt(FMOD_DSP_MULTIBAND_EQ_B_FILTER, FMOD_DSP_MULTIBAND_EQ_FILTER_LOWPASS_12DB);
    e->lofi->setParameterFloat(FMOD_DSP_MULTIBAND_EQ_B_FREQUENCY, 3400.0f);
    e->lofi->setWetDryMix(1.0f, 0.0f, 1.0f);
    e->music->addDSP(FMOD_CHANNELCONTROL_DSP_HEAD, e->lofi);
  }
  return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeHasResonance(JNIEnv*, jclass, jlong h) {
  return E(h)->sourcePlugin != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeLoadStem(JNIEnv* env, jclass, jlong h,
                                                                                    jint index, jshortArray pcm,
                                                                                    jint channels, jint rate,
                                                                                    jfloat gain) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  if (index >= static_cast<jint>(e->stems.size())) e->stems.resize(index + 1);
  Stem& stem = e->stems[index];
  stem.sound = soundFromPcm(e, env, pcm, channels, rate, true);
  stem.baseGain = gain;
  return stem.sound != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeStartStems(JNIEnv*, jclass, jlong h) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  unsigned long long clock = 0;
  e->master->getDSPClock(&clock, nullptr);
  int rate = 48000;
  e->system->getSoftwareFormat(&rate, nullptr, nullptr);
  const unsigned long long start = clock + static_cast<unsigned long long>(rate / 4);  // all stems on one sample
  for (Stem& s : e->stems) {
    if (s.sound == nullptr) continue;
    e->system->playSound(s.sound, e->music, true, &s.dry);
    e->system->playSound(s.sound, e->music, true, &s.wet.channel);
    s.dry->set3DLevel(0.0f);
    makeSpatial(e, s.wet);
    for (FMOD::Channel* c : {s.dry, s.wet.channel}) {
      c->setVolume(0.0f);
      c->setDelay(start, 0, false);
      c->setPaused(false);
    }
  }
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeSetStem(JNIEnv*, jclass, jlong h, jint index,
                                                                               jfloat gain, jfloat spatial, jfloat x,
                                                                               jfloat y, jfloat z, jfloat clarity) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  if (index < 0 || index >= static_cast<jint>(e->stems.size())) return;
  Stem& s = e->stems[index];
  if (s.dry == nullptr || s.wet.channel == nullptr) return;
  const float g = gain * s.baseGain;
  s.dry->setVolume(g * (1.0f - spatial));
  s.wet.channel->setVolume(g * spatial * 1.2f);
  s.wet.channel->setLowPassGain(0.15f + 0.85f * clarity * clarity);
  place(e, s.wet, x, y, z);
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativePlayVoice(JNIEnv* env, jclass, jlong h,
                                                                                 jshortArray pcm, jint rate,
                                                                                 jboolean spatial, jfloat x, jfloat y, jfloat z,
                                                                                 jfloat radio) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  releaseSpatial(e->voice);
  if (e->voiceSound != nullptr) e->voiceSound->release();
  e->voiceSound = soundFromPcm(e, env, pcm, 1, rate, false);
  if (e->voiceSound == nullptr) return;
  e->system->playSound(e->voiceSound, e->voices, true, &e->voice.channel);
  if (spatial) {
    makeSpatial(e, e->voice);
    place(e, e->voice, x, y, z);
  } else {
    e->voice.channel->set3DLevel(0.0f);
  }
  // Radio colour: a duller, band-limited voice for the more crushed characters.
  e->voice.channel->setLowPassGain(1.0f - 0.5f * radio);
  e->voice.channel->setPaused(false);
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeSetVoicePosition(JNIEnv*, jclass, jlong h,
                                                                                        jfloat x, jfloat y, jfloat z) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  place(e, e->voice, x, y, z);
}

JNIEXPORT jboolean JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeVoicePlaying(JNIEnv*, jclass, jlong h) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  if (e->voice.channel == nullptr) return JNI_FALSE;
  bool playing = false;
  if (e->voice.channel->isPlaying(&playing) != FMOD_OK) return JNI_FALSE;
  return playing ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeStopVoice(JNIEnv*, jclass, jlong h) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  releaseSpatial(e->voice);
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeSetMusicBus(JNIEnv*, jclass, jlong h,
                                                                                   jfloat volume, jfloat lofi) {
  Engine* e = E(h);
  e->music->setVolume(volume);
  if (e->lofi != nullptr) e->lofi->setWetDryMix(1.0f, lofi, 1.0f - lofi);
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeSetMaster(JNIEnv*, jclass, jlong h,
                                                                                 jfloat volume) {
  E(h)->master->setVolume(volume);
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeUpdate(JNIEnv*, jclass, jlong h) {
  Engine* e = E(h);
  std::lock_guard<std::mutex> guard(e->lock);
  e->system->update();
}

JNIEXPORT void JNICALL Java_com_ghostsignal_app_audio_FmodNative_nativeRelease(JNIEnv*, jclass, jlong h) {
  Engine* e = E(h);
  {
    std::lock_guard<std::mutex> guard(e->lock);
    releaseSpatial(e->voice);
    if (e->voiceSound != nullptr) e->voiceSound->release();
    for (Stem& s : e->stems) {
      if (s.dry != nullptr) s.dry->stop();
      releaseSpatial(s.wet);
      if (s.sound != nullptr) s.sound->release();
    }
    if (e->lofi != nullptr) e->lofi->release();
    if (e->listener != nullptr) e->listener->release();
    e->system->close();
    e->system->release();
  }
  delete e;
}

}  // extern "C"
