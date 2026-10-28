import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { createAccounts } from "../src/accounts.js";
import { createNpc } from "../src/npc.js";
import { loadEnvFile } from "../src/env.js";
import { limiter } from "../src/server.js";
import { createStt, wavRate } from "../src/stt.js";
import { pcmToWav } from "../src/tts.js";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-fixes-"));

test(".env file loads keys, and the real environment wins", () => {
  const file = path.join(tmp(), ".env");
  fs.writeFileSync(file, [
    "# a comment",
    "MODEL_API_KEY=sk-test-123",
    "export NPC_MODEL='claude-test'",
    'QUOTED="a b"',
    "not a setting",
    "EXISTING=from-file",
    "",
  ].join("\n"));
  const env = { EXISTING: "from-shell" };
  const loaded = loadEnvFile(file, env);
  assert.equal(env.MODEL_API_KEY, "sk-test-123");
  assert.equal(env.NPC_MODEL, "claude-test");
  assert.equal(env.QUOTED, "a b");
  assert.equal(env.EXISTING, "from-shell", "a value already set is never overwritten");
  assert.ok(!loaded.includes("EXISTING"));
  assert.deepEqual(loadEnvFile(path.join(tmp(), "missing.env"), {}), [], "no file is fine");
});

test("limiter forgets buckets that have fully refilled", () => {
  let now = 1_000_000;
  const allow = limiter(5, () => now);
  for (let i = 0; i < 150; i++) allow(`addr-${i}`);
  assert.equal(allow.size(), 150);
  now += 61_000; // every bucket above has refilled; none is worth remembering
  allow("fresh");
  assert.equal(allow.size(), 1);

  const one = limiter(1, () => now);
  assert.equal(one("a"), true);
  assert.equal(one("a"), false, "the limit itself still applies");
});

test("two racing sign-ups for one email create one account", async () => {
  const accounts = createAccounts({ dir: tmp() });
  const results = await Promise.allSettled([
    accounts.signUp({ email: "Race@Example.com", password: "first password", client: "app" }),
    accounts.signUp({ email: "race@example.com", password: "second password", client: "app" }),
  ]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  const rejected = results.find((r) => r.status === "rejected");
  assert.equal(rejected.reason.status, 409);
  assert.equal(accounts.list("").length, 1);
});

test("characters reply through Muse Spark, with persona and game state", async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return { ok: true, status: 200, json: async () => ({ choices: [{ message: { content: " Runner, **static** rising. " } }] }), text: async () => "" };
  };
  const mission = {
    id: "m",
    title: "GhostSignal",
    world: "A pirate broadcast over Bryant Park.",
    characters: [{ id: "vesper", name: "Vesper", role: "the handler", persona: "Dry and warm.", voice: {} }],
  };
  const body = { characterId: "vesper", utterance: "Which way do I go?", context: { phase: "HUNTING", collected: 0, total: 8 }, history: [] };
  const npc = createNpc({ apiKey: "mk-test", fetchImpl, log: { warn() {} } });
  assert.equal(npc.enabled, true);
  assert.equal(npc.model, "muse-spark-1.3");
  assert.equal(await npc.reply(mission, body), "Runner, static rising.");
  const req = JSON.parse(calls.at(-1).init.body);
  assert.match(calls.at(-1).url, /\/chat\/completions$/);
  assert.match(req.messages[0].content, /You are Vesper/);
  assert.match(req.messages[1].content, /The runner says: "Which way do I go\?"/);
  await assert.rejects(() => npc.reply(mission, { ...body, characterId: "nobody" }), (e) => e.status === 404);
});

test("speech clips are checked before they cost a transcription", async () => {
  assert.equal(wavRate(pcmToWav(Buffer.alloc(3200), 16_000)), 16_000);
  assert.throws(() => wavRate(pcmToWav(Buffer.alloc(3200), 44_100)), (e) => e.status === 415);
  assert.throws(() => wavRate(Buffer.from("not a wav")), (e) => e.status === 415);
  const stt = createStt({ meta: { enabled: true, transcribe: async (wav, opts) => `heard:${opts.keywords.join("+")}` } });
  assert.equal(await stt.transcribe(pcmToWav(Buffer.alloc(3200), 16_000), ["decode"]), "heard:decode");
  const off = createStt({ meta: { enabled: false, transcribe: async () => "" } });
  await assert.rejects(() => off.transcribe(pcmToWav(Buffer.alloc(3200), 16_000)), (e) => e.status === 503);
});
