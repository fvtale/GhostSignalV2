import assert from "node:assert/strict";
import { test } from "node:test";
import { createChatClient, createMeta } from "../src/meta.js";
import { createNpc } from "../src/npc.js";

const log = { warn() {} };
const FIREWORKS = "https://api.fireworks.ai/inference/v1";

function recorder() {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url: String(url), auth: init.headers.authorization, body: init.body });
    const body = String(url).endsWith("/asr/transcribe")
      ? { transcript: "heard" }
      : { choices: [{ message: { content: "a reply" } }] };
    return new Response(JSON.stringify(body), { status: 200 });
  };
  return { calls, fetchImpl };
}

test("characters run on their own key and service while transcription stays on Meta", async () => {
  const { calls, fetchImpl } = recorder();
  const shared = createMeta({ apiKey: "meta-key", fetchImpl, log });
  const chat = createChatClient({ shared, env: { NPC_API_KEY: "fw-key", NPC_API_BASE: FIREWORKS }, fetchImpl, log });

  await chat.chat({ model: "accounts/fireworks/models/muse-spark-open", system: "s", user: "u" });
  await shared.transcribe(Buffer.from("wav"));

  assert.equal(calls[0].url, `${FIREWORKS}/chat/completions`);
  assert.equal(calls[0].auth, "Bearer fw-key");
  assert.equal(JSON.parse(calls[0].body).model, "accounts/fireworks/models/muse-spark-open");
  assert.equal(calls[1].url, "https://api.meta.ai/v1/asr/transcribe");
  assert.equal(calls[1].auth, "Bearer meta-key");
});

test("without NPC_API_KEY characters share the client, base or no base", () => {
  const shared = createMeta({ apiKey: "meta-key", fetchImpl: recorder().fetchImpl, log });
  assert.equal(createChatClient({ shared, env: {} }), shared);
  assert.equal(createChatClient({ shared, env: { NPC_API_BASE: FIREWORKS } }), shared);
  assert.equal(createChatClient({ shared, env: { NPC_API_KEY: "" } }), shared);
});

test("NPC_API_KEY alone stays on the default service, with its own key", async () => {
  const { calls, fetchImpl } = recorder();
  const shared = createMeta({ apiKey: "meta-key", fetchImpl, log });
  const chat = createChatClient({ shared, env: { NPC_API_KEY: "other-key" }, fetchImpl, log });
  await chat.chat({ model: "m", system: "s", user: "u" });
  assert.equal(calls[0].url, "https://api.meta.ai/v1/chat/completions");
  assert.equal(calls[0].auth, "Bearer other-key");
});

test("a client and the character agent say where their calls go", () => {
  const { fetchImpl } = recorder();
  const shared = createMeta({ apiKey: "meta-key", fetchImpl, log });
  assert.equal(shared.host, "api.meta.ai");
  const chat = createChatClient({ shared, env: { NPC_API_KEY: "fw-key", NPC_API_BASE: FIREWORKS }, fetchImpl, log });
  assert.equal(createNpc({ meta: chat, log }).host, "api.fireworks.ai");
});

test("characters can work with no Meta key at all", () => {
  const { fetchImpl } = recorder();
  const shared = createMeta({ apiKey: "", fetchImpl, log });
  const chat = createChatClient({ shared, env: { NPC_API_KEY: "fw-key" }, fetchImpl, log });
  assert.equal(shared.enabled, false);
  assert.equal(createNpc({ meta: chat, log }).enabled, true);
});
