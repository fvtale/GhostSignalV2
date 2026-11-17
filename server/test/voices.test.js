import assert from "node:assert/strict";
import { test } from "node:test";

// Browser stubs must exist before the player module loads.
const spoken = [];
let voiceList = [];
globalThis.window = globalThis;
globalThis.SpeechSynthesisUtterance = class {
  constructor(text) { this.text = text; }
};
globalThis.speechSynthesis = {
  getVoices: () => voiceList,
  addEventListener: () => {},
  speak: (line) => { spoken.push(line); line.onend?.(); },
  cancel: () => {},
};
const { Voices, chooseVoice, treatmentFor } = await import("../play/js/voices.js");

const v = (name, voiceURI, lang, localService) => ({ name, voiceURI, lang, localService });

// Edge on Windows: a few local voices plus many distinct natural voices.
const EDGE_VOICES = [
  v("Microsoft David - English (United States)", "david", "en-US", true),
  v("Microsoft Zira - English (United States)", "zira", "en-US", true),
  v("Microsoft Aria Online (Natural) - English (United States)", "aria", "en-US", false),
  v("Microsoft Jenny Online (Natural) - English (United States)", "jenny", "en-US", false),
  v("Microsoft Ana Online (Natural) - English (United States)", "ana", "en-US", false),
  v("Microsoft Christopher Online (Natural) - English (United States)", "christopher", "en-US", false),
  v("Microsoft Eric Online (Natural) - English (United States)", "eric", "en-US", false),
  v("Microsoft Roger Online (Natural) - English (United States)", "roger", "en-US", false),
  v("Microsoft Guy Online (Natural) - English (United States)", "guy", "en-US", false),
];

// Firefox on Windows: typically only the two local OS voices.
const FIREFOX_VOICES = [
  v("Microsoft David - English (United States)", "david", "en-US", true),
  v("Microsoft Zira - English (United States)", "zira", "en-US", true),
];

test("characters are cast by type, not by blind hash (Edge voice list)", () => {
  const taken = new Set();
  const cast = {};
  for (const speaker of ["vesper", "archivist", "mari", "kilo", "hush", "smartspecs"]) {
    cast[speaker] = chooseVoice(speaker, EDGE_VOICES, taken);
    taken.add(cast[speaker].voiceURI);
  }
  // Vesper: a capable female leader, not the generic default.
  assert.equal(cast.vesper.voiceURI, "aria");
  // Archivist: an American male (wise, weird, New York).
  assert.equal(cast.archivist.voiceURI, "christopher");
  assert.match(cast.archivist.lang, /^en-US/);
  // Zion: a lower-range male, and not the Archivist's voice.
  assert.ok(["roger", "eric", "guy", "david"].includes(cast.mari.voiceURI), cast.mari.voiceURI);
  assert.notEqual(cast.mari.voiceURI, cast.archivist.voiceURI);
  // Kilo: female, distinct from Vesper when the pool allows it.
  assert.equal(cast.kilo.voiceURI, "ana");
  // Everyone who can be distinct, is.
  const uris = Object.values(cast).map((voice) => voice.voiceURI);
  assert.equal(new Set(uris).size, uris.length, uris.join(","));
});

test("treatments carry the character, not just the voice", () => {
  const authored = { pitch: 1.05, rate: 1.05 };
  const zion = treatmentFor("mari", authored);
  assert.ok(zion.pitch <= 0.85, `Zion must sit in a lower male range, got ${zion.pitch}`);
  const archivist = treatmentFor("archivist", authored);
  assert.ok(archivist.pitch <= 0.8 && archivist.rate < 1, "Archivist: low and unhurried");
  const vesper = treatmentFor("vesper", authored);
  assert.ok(vesper.pitch >= 0.85 && vesper.pitch <= 0.95, "Vesper: grounded female, not chipmunk, not male");
});

test("two-voice browsers still get the genders right (Firefox voice list)", () => {
  assert.equal(chooseVoice("vesper", FIREFOX_VOICES, new Set()).voiceURI, "zira");
  assert.equal(chooseVoice("kilo", FIREFOX_VOICES, new Set()).voiceURI, "zira");
  assert.equal(chooseVoice("archivist", FIREFOX_VOICES, new Set()).voiceURI, "david");
  assert.equal(chooseVoice("mari", FIREFOX_VOICES, new Set()).voiceURI, "david");
  assert.equal(chooseVoice("hush", FIREFOX_VOICES, new Set()).voiceURI, "david");
});

test("a character keeps their voice when the voice list grows mid-run", async () => {
  voiceList = [...FIREFOX_VOICES];
  const voices = new Voices(null, "test");
  const say = (speaker) => voices.say({ speaker, text: "a line of dialogue here", pitch: 1, rate: 1 }, 0);
  await say("vesper");
  voiceList = [...EDGE_VOICES]; // voices finish loading, list reorders and grows
  await say("vesper");
  assert.equal(spoken.length, 2);
  assert.equal(spoken[0].voice.voiceURI, spoken[1].voice.voiceURI);
  assert.equal(spoken[0].voice.voiceURI, "zira");
});
