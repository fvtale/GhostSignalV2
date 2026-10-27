import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import crypto from "node:crypto";
import { createAccounts } from "../src/accounts.js";
import { createAdmin } from "../src/admin.js";
import { fromLemonSqueezy, fromStripe, paymentUrl } from "../src/billing.js";
import { probeAudio } from "../src/audio.js";
import { loadMission } from "../src/mission.js";
import { cleanReply, createNpc, systemPrompt, userTurn } from "../src/npc.js";
import { clientIp, createServer } from "../src/server.js";
import { createStore } from "../src/store.js";
import { createTts, pcmToWav } from "../src/tts.js";

const mission = loadMission();
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-"));

/** Stands in for the Anthropic client and records what it was asked. */
function fakeClient(respond) {
  const calls = [];
  return {
    calls,
    beta: {
      messages: {
        create: async (req) => {
          calls.push(req);
          return respond(req);
        },
      },
    },
  };
}

const textResponse = (obj) => ({ stop_reason: "end_turn", content: [{ type: "text", text: JSON.stringify(obj) }] });

let server;
let base;
let client;
let fetchCalls = 0;

before(async () => {
  client = fakeClient(() => textResponse({ reply: "Left at the lions, runner. **Fast.**" }));
  const npc = createNpc({ client, log: { warn() {} } });
  const tts = createTts({
    apiKey: "test",
    defaultVoice: "voice-1",
    cacheDir: path.join(dataDir, "tts"),
    fetchImpl: async () => {
      fetchCalls++;
      return new Response(new Uint8Array(4800), { status: 200 });
    },
  });
  server = createServer({
    mission,
    npc,
    tts,
    store: createStore(dataDir),
    eventToken: "event",
    operatorToken: "operator",
    billing: {
      adminEmails: ["boss@example.com"],
      defaults: { paymentLink: "https://buy.stripe.com/test_abc", priceLabel: "$4 a month" },
      secrets: { stripe: "whsec_test", lemonsqueezy: "ls_test" },
    },
    log: { warn() {}, error() {}, info() {} },
  });
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const call = (p, { method = "GET", token = "event", body } = {}) =>
  fetch(base + p, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

const npcBody = {
  missionId: mission.id,
  characterId: "vesper",
  signalId: "heartbeat",
  utterance: "Which way do I go?",
  context: { phase: "HUNTING", objective: "Find The Heartbeat", signalName: "The Heartbeat", distance: "about 80 metres", direction: "at 2 o'clock", collected: 0, total: 8 },
  history: [{ role: "runner", text: "Hello?" }, { role: "character", text: "Runner. You made it." }],
};

describe("health and auth", () => {
  it("reports configured features without a token", async () => {
    const res = await fetch(`${base}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.mission, mission.id);
    assert.equal(body.npc, true);
    assert.equal(body.tts, true);
  });

  it("takes the client address from the proxy only when told to, and only from loopback", () => {
    const req = (remoteAddress, xff) => ({ socket: { remoteAddress }, headers: xff === undefined ? {} : { "x-forwarded-for": xff } });
    // Caddy appends the address it saw; whatever the client sent before that can be made up.
    assert.equal(clientIp(req("127.0.0.1", "6.6.6.6, 203.0.113.9"), true), "203.0.113.9");
    assert.equal(clientIp(req("::ffff:127.0.0.1", "2001:db8::1"), true), "2001:db8::1");
    assert.equal(clientIp(req("127.0.0.1"), true), "127.0.0.1");
    // Not behind a proxy, or reached directly: the header is ignored.
    assert.equal(clientIp(req("127.0.0.1", "203.0.113.9"), false), "127.0.0.1");
    assert.equal(clientIp(req("198.51.100.4", "203.0.113.9"), true), "198.51.100.4");
  });

  it("rejects a bad event token", async () => {
    const res = await call("/v1/npc", { method: "POST", token: "nope", body: npcBody });
    assert.equal(res.status, 401);
  });
});

describe("character agent", () => {
  it("replies in character with a cleaned, speakable line", async () => {
    const res = await call("/v1/npc", { method: "POST", body: npcBody });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.reply, "Left at the lions, runner. Fast.");

    const req = client.calls.at(-1);
    assert.equal(req.model, "claude-opus-5-5");
    assert.equal(req.output_config.effort, "low");
    assert.equal(req.output_config.format.type, "json_schema");
    assert.equal(req.fallbacks, "default");
    assert.deepEqual(req.betas, ["server-side-fallback-2026-07-01"]);
    assert.match(req.system, /You are Vesper/);
    assert.match(req.system, /Quiet Systems/);
    assert.match(req.messages[0].content, /about 80 metres from the runner, at 2 o'clock/);
    assert.match(req.messages[0].content, /Runner: Hello\?/);
  });

  it("answers a refusal with an in-world line", async () => {
    const npc = createNpc({ client: fakeClient(() => ({ stop_reason: "refusal", stop_details: { category: null }, content: [] })), log: { warn() {} } });
    const reply = await npc.reply(mission, npcBody);
    assert.match(reply, /runner|HUSH/);
  });

  it("404s an unknown character", async () => {
    const res = await call("/v1/npc", { method: "POST", body: { ...npcBody, characterId: "nobody" } });
    assert.equal(res.status, 404);
  });

  it("keeps per-request details out of the cacheable system prompt", () => {
    const vesper = mission.characters.find((c) => c.id === "vesper");
    assert.doesNotMatch(systemPrompt(mission, vesper), /80 metres/);
    assert.match(userTurn(npcBody), /The runner says: "Which way do I go\?"/);
    assert.equal(cleanReply('"*Hi*  there"'), "Hi there");
  });
});

describe("voices", () => {
  it("returns WAV and caches it", async () => {
    const before = fetchCalls;
    const res = await call("/v1/tts", { method: "POST", body: { missionId: mission.id, characterId: "kilo", text: "Passphrase. Now." } });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "audio/wav");
    const wav = Buffer.from(await res.arrayBuffer());
    assert.equal(wav.toString("ascii", 0, 4), "RIFF");
    assert.equal(wav.length, 44 + 4800);
    await call("/v1/tts", { method: "POST", body: { missionId: mission.id, characterId: "kilo", text: "Passphrase. Now." } });
    assert.equal(fetchCalls, before + 1, "second render comes from the cache");
  });

  it("builds a correct WAV header", () => {
    const wav = pcmToWav(Buffer.alloc(100), 24000);
    assert.equal(wav.readUInt32LE(24), 24000);
    assert.equal(wav.readUInt32LE(40), 100);
  });
});

describe("routes and broadcasts", () => {
  const route = { missionId: mission.id, name: "Showcase", placements: { heartbeat: { location: { lat: 40.75, lon: -73.98 }, radiusM: 45, lockRadiusM: 25 } } };

  it("only the operator can publish a route", async () => {
    assert.equal((await call(`/v1/routes/${mission.id}`, { method: "PUT", body: route })).status, 403);
    assert.equal((await call(`/v1/routes/${mission.id}`, { method: "PUT", token: "operator", body: route })).status, 204);
    const got = await (await call(`/v1/routes/${mission.id}`)).json();
    assert.equal(got.placements.heartbeat.lockRadiusM, 25);
  });

  it("delivers live broadcasts after a timestamp", async () => {
    const t0 = Date.now() - 1;
    assert.equal((await call("/v1/broadcasts", { method: "POST", body: { speaker: "vesper", text: "HUSH is at the fountain." } })).status, 403);
    const sent = await call("/v1/broadcasts", { method: "POST", token: "operator", body: { speaker: "vesper", text: "HUSH is at the fountain." } });
    assert.equal(sent.status, 201);
    const { broadcasts } = await (await call(`/v1/broadcasts?since=${t0}`)).json();
    assert.equal(broadcasts.at(-1).text, "HUSH is at the fountain.");
  });
});

describe("admin sign-in", () => {
  /** Stands in for the DataRail account service. */
  function fakeDatarail({ isAdmin = true } = {}) {
    const calls = [];
    const fetchImpl = async (url, init) => {
      calls.push({ url, init });
      if (url.endsWith("/auth/logout")) return new Response("{}", { status: 200 });
      const { email, password } = JSON.parse(init.body);
      if (email !== "lyn@example.com" || password !== "right") return new Response('{"detail":"no"}', { status: 401 });
      return new Response(JSON.stringify({ account: {}, principal: { display: email, is_admin: isAdmin, via: "account", grants: [], apps: [] } }), {
        status: 200,
        headers: { "set-cookie": "datarail_storage_session=s_abc.def; HttpOnly; Path=/; SameSite=lax" },
      });
    };
    return { calls, fetchImpl };
  }

  it("lets a DataRail admin in and closes the DataRail session behind it", async () => {
    const dr = fakeDatarail();
    const admin = createAdmin({ datarailApi: "https://drive.example/storage/api/", fetchImpl: dr.fetchImpl });
    const s = await admin.login({ provider: "datarail", email: " lyn@example.com ", password: "right" });
    assert.match(s.session, /^gsa_/);
    assert.deepEqual(s.who, { display: "lyn@example.com", via: "datarail" });
    assert.ok(admin.session(s.session));
    assert.equal(dr.calls[0].url, "https://drive.example/storage/api/auth/login");
    await new Promise((r) => setImmediate(r));
    assert.equal(dr.calls[1].url, "https://drive.example/storage/api/auth/logout");
    assert.equal(dr.calls[1].init.headers.cookie, "datarail_storage_session=s_abc.def");
    admin.close(s.session);
    assert.equal(admin.session(s.session), null);
  });

  it("turns away a DataRail account that isn't an admin, and a wrong password", async () => {
    const admin = createAdmin({ datarailApi: "https://drive.example/storage/api", fetchImpl: fakeDatarail({ isAdmin: false }).fetchImpl });
    const dr = { provider: "datarail", email: "lyn@example.com" };
    await assert.rejects(admin.login({ ...dr, password: "right" }), { status: 403 });
    await assert.rejects(admin.login({ ...dr, password: "wrong" }), { status: 401 });
    await assert.rejects(createAdmin({}).login({ ...dr, password: "right" }), { status: 501 });
  });

  it("expires sessions", async () => {
    let t = 1_000;
    const admin = createAdmin({ operatorToken: "op", now: () => t });
    const s = await admin.login({ token: "op" });
    t += 12 * 60 * 60 * 1000 + 1;
    assert.equal(admin.session(s.session), null);
  });

  it("swaps the operator token for a session that works as admin and as an event token", async () => {
    assert.equal((await call("/v1/admin/login", { method: "POST", token: "", body: { token: "nope" } })).status, 401);
    const res = await call("/v1/admin/login", { method: "POST", token: "", body: { token: "operator" } });
    assert.equal(res.status, 200);
    const { session } = await res.json();
    assert.equal((await call("/v1/admin/me", { token: session })).status, 200);
    assert.equal((await call(`/v1/routes/${mission.id}`, { token: session })).status === 401, false);
    assert.equal((await call("/v1/admin/mission", { token: "event" })).status, 403);
    const m = await (await call("/v1/admin/mission", { token: session })).json();
    assert.equal(m.signals.length, mission.signals.length);
    assert.deepEqual(m.bundledSoundtracks.map((t) => t.id), ["dead-channel"]);
    assert.equal((await call("/v1/admin/logout", { method: "POST", token: session })).status, 204);
    assert.equal((await call("/v1/admin/me", { token: session })).status, 401);
  });
});

describe("admin page", () => {
  it("serves the page, its script and the vendored map with a strict CSP", async () => {
    const page = await fetch(`${base}/admin`);
    assert.equal(page.status, 200);
    assert.match(page.headers.get("content-security-policy"), /default-src 'self'/);
    assert.match(await page.text(), /GhostSignal Admin/);
    for (const p of ["/admin/admin.js", "/admin/admin.css", "/admin/vendor/leaflet/leaflet.js"]) {
      assert.equal((await fetch(base + p)).status, 200, p);
    }
  });

  it("serves nothing outside its folder", async () => {
    for (const p of ["/admin/../src/index.js", "/admin/%2e%2e/src/index.js", "/admin/vendor/leaflet/LICENSE", "/admin/.hidden.js", "/admin//admin.js"]) {
      assert.notEqual((await fetch(base + p)).status, 200, p);
    }
  });
});

describe("deployed soundtracks", () => {
  const loopS = (mission.music.loopBars * mission.music.beatsPerBar * 60) / mission.music.bpm;
  const wav = (seconds, rate = 8000) => pcmToWav(Buffer.alloc(Math.round(seconds * rate) * 2), rate);
  const upload = (bytes, token = "operator") =>
    fetch(`${base}/v1/blobs?name=stem.wav`, { method: "PUT", headers: { authorization: `Bearer ${token}`, "content-type": "audio/wav" }, body: bytes });
  const slots = mission.signals.map((s) => s.stem);
  let blob;
  let shortBlob;

  before(async () => {
    blob = await (await upload(wav(loopS))).json();
    shortBlob = await (await upload(wav(loopS - 1))).json();
  });

  it("stores an upload by checksum and reports exactly how long it plays", async () => {
    assert.match(blob.name, /^[0-9a-f]{64}\.wav$/);
    assert.equal(blob.format, "wav");
    assert.ok(Math.abs(blob.durationS - loopS) < 0.001, `${blob.durationS} vs ${loopS}`);
    const again = await (await upload(wav(loopS))).json();
    assert.equal(again.name, blob.name);
  });

  it("only admins upload, and only audio", async () => {
    assert.equal((await upload(wav(1), "event")).status, 403);
    assert.equal((await upload(Buffer.from("not audio at all"))).status, 415);
  });

  it("keeps drafts from phones and needs every stem to publish", async () => {
    const one = { title: "Night Bus", music: { bpm: mission.music.bpm, stems: [{ id: slots[0], blob: blob.name }] } };
    const draft = await call(`/v1/tracks/${mission.id}/night-bus`, { method: "PUT", token: "operator", body: one });
    assert.equal(draft.status, 200);
    assert.equal((await draft.json()).track.published, false);
    assert.deepEqual((await (await call(`/v1/tracks/${mission.id}`)).json()).soundtracks, []);
    const all = await (await call(`/v1/tracks/${mission.id}?all=1`, { token: "operator" })).json();
    assert.deepEqual(all.soundtracks.map((t) => t.id), ["night-bus"]);
    const premature = await call(`/v1/tracks/${mission.id}/night-bus`, { method: "PUT", token: "operator", body: { ...one, published: true } });
    assert.equal(premature.status, 400);
    assert.match((await premature.json()).error, /needs every stem/);
  });

  it("publishes a full track to phones in the app's own soundtrack shape", async () => {
    const stems = slots.map((id, i) => ({ id, blob: i === 7 ? shortBlob.name : blob.name, gainDb: -1 }));
    const res = await call(`/v1/tracks/${mission.id}/night-bus`, {
      method: "PUT",
      token: "operator",
      body: { title: "Night Bus", description: "Late, slow, sodium lights.", unlockAfterCompletions: 0, published: true, music: { bpm: mission.music.bpm, beatsPerBar: 4, loopBars: 8, stems } },
    });
    assert.equal(res.status, 200);
    const { track, warnings } = await res.json();
    assert.equal(warnings.length, 1, "the short stem gets a warning");
    assert.match(warnings[0], /padded with silence/);
    assert.equal(track.music.title, "Night Bus");

    const phone = await (await call(`/v1/tracks/${mission.id}`)).json();
    assert.equal(phone.missionId, mission.id);
    const t = phone.soundtracks.single ?? phone.soundtracks[0];
    assert.equal(t.id, "night-bus");
    assert.deepEqual(t.music.stems.map((s) => s.id), slots);
    const first = t.music.stems[0];
    assert.equal(first.file, `server:${blob.name}`);
    assert.equal(first.name, mission.signals[0].name);
    assert.equal(first.sha256, blob.sha256);

    const audio = await call(`/v1/blobs/${blob.name}`);
    assert.equal(audio.status, 200);
    assert.equal(audio.headers.get("content-type"), "audio/wav");
    const bytes = Buffer.from(await audio.arrayBuffer());
    assert.equal(bytes.length, blob.bytes);
    assert.equal(probeAudio(bytes).format, "wav");
    assert.equal((await call(`/v1/blobs/${blob.name}`, { token: "nope" })).status, 200, "a published free stem is public");
    assert.equal((await call(`/v1/blobs/${"0".repeat(64)}.ogg`)).status, 404);
  });

  it("won't take the name of a soundtrack that ships with the app", async () => {
    for (const id of ["dead-channel", "original", "Bad_Id"]) {
      const res = await call(`/v1/tracks/${mission.id}/${id}`, { method: "PUT", token: "operator", body: { title: "x", music: { bpm: 90, stems: [] } } });
      assert.equal(res.status, 400, id);
    }
  });

  it("takes a track down", async () => {
    assert.equal((await call(`/v1/tracks/${mission.id}/night-bus`, { method: "DELETE", token: "event" })).status, 403);
    assert.equal((await call(`/v1/tracks/${mission.id}/night-bus`, { method: "DELETE", token: "operator" })).status, 204);
    assert.deepEqual((await (await call(`/v1/tracks/${mission.id}`)).json()).soundtracks, []);
    assert.equal((await call(`/v1/tracks/${mission.id}/night-bus`, { method: "DELETE", token: "operator" })).status, 404);
  });
});

describe("route edits from the phone and the admin page", () => {
  const at = (lat, lon, radiusM = 45, lockRadiusM = 28) => ({ location: { lat, lon }, radiusM, lockRadiusM });
  const put = (body) => call(`/v1/routes/${mission.id}`, { method: "PUT", token: "operator", body: { missionId: mission.id, ...body } });
  const get = async () => (await call(`/v1/routes/${mission.id}`)).json();

  it("merges signal by signal, so neither side undoes the other", async () => {
    assert.equal((await put({ placements: { heartbeat: at(40.7531, -73.9822) } })).status, 204);
    assert.equal((await put({ placements: { fog: at(40.7541, -73.9840) }, labels: { stacks: { name: "The Card Catalog", place: "" } } })).status, 204);
    // The phone's route editor sends everything it has, including an empty labels map.
    assert.equal((await put({ name: "Venue route", placements: { heartbeat: at(40.7532, -73.9823) }, labels: {}, updatedAtMs: 5 })).status, 204);
    const r = await get();
    assert.equal(r.placements.heartbeat.location.lat, 40.7532);
    assert.equal(r.placements.fog.location.lat, 40.7541);
    assert.deepEqual(r.labels.stacks, { name: "The Card Catalog", place: "" });
    assert.ok(r.updatedAtMs > 5, "the server stamps its own time");
  });

  it("puts a signal back as written with null", async () => {
    assert.equal((await put({ placements: { fog: null }, labels: { stacks: null } })).status, 204);
    const r = await get();
    assert.equal(r.placements.fog, undefined);
    assert.equal(r.labels.stacks, undefined);
  });

  it("rejects what a phone couldn't play", async () => {
    assert.equal((await put({ placements: { nowhere: at(40.75, -73.98) } })).status, 400);
    assert.equal((await put({ placements: { fog: at(40.75, -73.98, 20, 30) } })).status, 400);
    assert.equal((await put({ placements: { fog: at(140, -73.98) } })).status, 400);
    assert.equal((await put({ labels: { fog: { name: "x".repeat(61) } } })).status, 400);
    assert.equal((await call("/v1/routes/another-mission", { method: "PUT", token: "operator", body: { missionId: "another-mission" } })).status, 404);
  });
});

describe("accounts", () => {
  const acct = (p, { method = "GET", token, body } = {}) =>
    fetch(base + p, {
      method,
      headers: { "content-type": "application/json", ...(token ? { "x-account-token": token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  let player;

  it("signs players up without the event token, and checks what they send", async () => {
    assert.equal((await acct("/v1/account/signup", { method: "POST", body: { email: "not-an-email", password: "longenough1" } })).status, 400);
    assert.equal((await acct("/v1/account/signup", { method: "POST", body: { email: "runner@example.com", password: "short" } })).status, 400);
    const res = await acct("/v1/account/signup", { method: "POST", body: { email: " Runner@Example.com ", password: "correct horse", client: "app" } });
    assert.equal(res.status, 201);
    player = await res.json();
    assert.match(player.token, /^gsu_/);
    assert.equal(player.account.email, "runner@example.com");
    assert.equal(player.account.role, "player");
    assert.equal(player.account.premium.active, false);
    assert.equal((await acct("/v1/account/signup", { method: "POST", body: { email: "runner@example.com", password: "correct horse" } })).status, 409);
  });

  it("signs in, and says nothing about whether an email exists", async () => {
    const wrong = await acct("/v1/account/login", { method: "POST", body: { email: "runner@example.com", password: "wrong password" } });
    const nobody = await acct("/v1/account/login", { method: "POST", body: { email: "nobody@example.com", password: "wrong password" } });
    assert.equal(wrong.status, 401);
    assert.equal(nobody.status, 401);
    assert.equal((await wrong.json()).error, (await nobody.json()).error);
    const ok = await acct("/v1/account/login", { method: "POST", body: { email: "runner@example.com", password: "correct horse" } });
    assert.equal(ok.status, 200);
    assert.equal((await acct("/v1/account/me")).status, 401);
    const me = await (await acct("/v1/account/me", { token: player.token })).json();
    assert.equal(me.account.id, player.account.id);
  });

  it("offers the payment link with the account attached", async () => {
    const me = await (await acct("/v1/account/me", { token: player.token })).json();
    const url = new URL(me.payment.url);
    assert.equal(url.host, "buy.stripe.com");
    assert.equal(url.searchParams.get("client_reference_id"), player.account.id);
    assert.equal(url.searchParams.get("prefilled_email"), "runner@example.com");
    assert.equal(me.payment.priceLabel, "$4 a month");
    const ls = new URL(paymentUrl("https://datarail.lemonsqueezy.com/buy/abc", { id: "u_1", email: "a@b.co" }));
    assert.equal(ls.searchParams.get("checkout[custom][account_id]"), "u_1");
    assert.equal(ls.searchParams.get("checkout[email]"), "a@b.co");
    assert.equal(paymentUrl("https://example.com/pay", { id: "u_1", email: "a@b.co" }), "https://example.com/pay");
  });

  it("keeps sessions across a restart, storing only their hashes", () => {
    const again = createAccounts({ dir: dataDir });
    assert.equal(again.session(player.token)?.account.email, "runner@example.com");
    assert.equal(fs.readFileSync(path.join(dataDir, "sessions.json"), "utf8").includes(player.token), false);
    assert.equal(fs.readFileSync(path.join(dataDir, "accounts.json"), "utf8").includes("correct horse"), false);
  });

  it("only counts finished runs upward", async () => {
    const up = await acct("/v1/account/progress", { method: "POST", token: player.token, body: { missionId: mission.id, completions: 3 } });
    assert.deepEqual(await up.json(), { completions: 3 });
    const down = await acct("/v1/account/progress", { method: "POST", token: player.token, body: { missionId: mission.id, completions: 1 } });
    assert.deepEqual(await down.json(), { completions: 3 });
  });

  it("lets an ADMIN_EMAILS account into the admin page, and not a player", async () => {
    await acct("/v1/account/signup", { method: "POST", body: { email: "boss@example.com", password: "boss password" } });
    const boss = await call("/v1/admin/login", { method: "POST", token: "", body: { email: "boss@example.com", password: "boss password" } });
    assert.equal(boss.status, 200);
    const { session, who } = await boss.json();
    assert.equal(who.via, "ghostsignal");
    const runner = await call("/v1/admin/login", { method: "POST", token: "", body: { email: "runner@example.com", password: "correct horse" } });
    assert.equal(runner.status, 403);

    const list = await (await call("/v1/admin/accounts?q=example", { token: session })).json();
    assert.equal(list.counts.total, 2);
    assert.equal(list.counts.admins, 1);
    const me = list.accounts.find((a) => a.email === "boss@example.com");
    const selfDemote = await call(`/v1/admin/accounts/${me.id}`, { method: "PUT", token: session, body: { role: "player" } });
    assert.equal(selfDemote.status, 409);
  });

  it("lets an admin grant premium, make admins and disable accounts", async () => {
    const grant = await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { premium: true } });
    assert.equal((await grant.json()).premium.source, "grant");
    let me = await (await acct("/v1/account/me", { token: player.token })).json();
    assert.equal(me.account.premium.active, true);
    assert.equal(me.payment.url, null, "no payment link once premium");
    await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { premium: false } });
    me = await (await acct("/v1/account/me", { token: player.token })).json();
    assert.equal(me.account.premium.active, false);

    const made = await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { role: "admin" } });
    assert.equal((await made.json()).role, "admin");
    const s = await (await call("/v1/admin/login", { method: "POST", token: "", body: { email: "runner@example.com", password: "correct horse" } })).json();
    assert.equal((await call("/v1/admin/me", { token: s.session })).status, 200);
    await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { role: "player" } });
    assert.equal((await call("/v1/admin/me", { token: s.session })).status, 401, "the admin session ends with the role");
  });

  it("changes a password and signs out everywhere else", async () => {
    const other = await (await acct("/v1/account/login", { method: "POST", body: { email: "runner@example.com", password: "correct horse" } })).json();
    assert.equal((await acct("/v1/account/password", { method: "POST", token: player.token, body: { current: "nope", next: "new password 1" } })).status, 403);
    assert.equal((await acct("/v1/account/password", { method: "POST", token: player.token, body: { current: "correct horse", next: "new password 1" } })).status, 204);
    assert.equal((await acct("/v1/account/me", { token: other.token })).status, 401);
    assert.equal((await acct("/v1/account/me", { token: player.token })).status, 200);
  });

  describe("payments", () => {
    const stripe = (evt, secret = "whsec_test") => {
      const body = JSON.stringify(evt);
      const t = Math.floor(Date.now() / 1000);
      const sig = crypto.createHmac("sha256", secret).update(`${t}.${body}`).digest("hex");
      return fetch(`${base}/v1/billing/stripe`, { method: "POST", headers: { "stripe-signature": `t=${t},v1=${sig}`, "content-type": "application/json" }, body });
    };
    const lemon = (evt, secret = "ls_test") => {
      const body = JSON.stringify(evt);
      const sig = crypto.createHmac("sha256", secret).update(body).digest("hex");
      return fetch(`${base}/v1/billing/lemonsqueezy`, { method: "POST", headers: { "x-signature": sig, "content-type": "application/json" }, body });
    };
    const premiumOf = async () => (await (await acct("/v1/account/me", { token: player.token })).json()).account.premium;

    it("turns premium on when the account's Stripe checkout completes, once", async () => {
      const evt = {
        id: "evt_1",
        type: "checkout.session.completed",
        data: { object: { client_reference_id: player.account.id, payment_status: "paid", customer: "cus_9", subscription: "sub_9", customer_details: { email: "runner@example.com" } } },
      };
      assert.equal((await stripe(evt, "wrong secret")).status, 400);
      assert.equal((await stripe(evt)).status, 200);
      const p = await premiumOf();
      assert.deepEqual([p.active, p.source, p.provider], [true, "payment", "stripe"]);
      assert.equal((await (await stripe(evt)).json()).duplicate, true);
    });

    it("turns it off when the subscription ends, but never undoes an admin's grant", async () => {
      await stripe({ id: "evt_2", type: "customer.subscription.deleted", data: { object: { id: "sub_9", customer: "cus_9", status: "canceled" } } });
      assert.equal((await premiumOf()).active, false);
      await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { premium: true } });
      await stripe({ id: "evt_3", type: "customer.subscription.deleted", data: { object: { id: "sub_9", customer: "cus_9" } } });
      assert.equal((await premiumOf()).active, true);
      await call(`/v1/admin/accounts/${player.account.id}`, { method: "PUT", token: "operator", body: { premium: false } });
    });

    it("follows a Lemon Squeezy subscription through cancellation to expiry", async () => {
      const sub = (status, extra = {}) => ({
        meta: { event_name: status === "expired" ? "subscription_expired" : "subscription_updated", custom_data: { account_id: player.account.id } },
        data: { id: 77, attributes: { status, customer_id: 5, user_email: "runner@example.com", urls: { customer_portal: "https://datarail.lemonsqueezy.com/billing" }, ...extra } },
      });
      assert.equal((await lemon({ ...sub("active"), meta: { ...sub("active").meta, event_name: "subscription_created" } })).status, 200);
      let p = await premiumOf();
      assert.deepEqual([p.active, p.provider, p.manageUrl], [true, "lemonsqueezy", "https://datarail.lemonsqueezy.com/billing"]);
      const ends = new Date(Date.now() + 5 * 86400_000).toISOString();
      await lemon(sub("cancelled", { ends_at: ends }));
      p = await premiumOf();
      assert.equal(p.active, true, "paid-up time is kept after cancelling");
      assert.equal(p.untilMs, Date.parse(ends));
      await lemon(sub("expired"));
      assert.equal((await premiumOf()).active, false);
    });

    it("holds a payment it can't match for an admin to apply", async () => {
      await stripe({ id: "evt_4", type: "checkout.session.completed", data: { object: { payment_status: "paid", customer: "cus_x", customer_details: { email: "someone@else.com" } } } });
      const { unclaimed } = await (await call("/v1/admin/accounts", { token: "operator" })).json();
      const u = unclaimed.find((x) => x.email === "someone@else.com");
      assert.ok(u);
      const applied = await call(`/v1/admin/unclaimed/${u.id}`, { method: "POST", token: "operator", body: { accountId: player.account.id } });
      assert.equal(applied.status, 200);
      assert.equal((await premiumOf()).active, true);
      assert.equal((await call(`/v1/admin/unclaimed/${u.id}`, { method: "DELETE", token: "operator" })).status, 404);
    });

    it("reads provider events the way it should", () => {
      assert.equal(fromStripe({ type: "checkout.session.completed", data: { object: { payment_status: "unpaid" } } }), null);
      assert.equal(fromStripe({ type: "invoice.paid", data: { object: {} } }), null);
      assert.equal(fromStripe({ id: "e", type: "customer.subscription.updated", data: { object: { customer: "c", status: "past_due" } } }).active, true);
      assert.equal(fromLemonSqueezy({ meta: { event_name: "subscription_payment_success" }, data: {} }, Buffer.from("x")), null);
      assert.equal(fromLemonSqueezy({ meta: { event_name: "order_created" }, data: { attributes: { status: "pending" } } }, Buffer.from("y")), null);
      const sneaky = fromLemonSqueezy({ meta: { event_name: "subscription_updated" }, data: { id: 1, attributes: { status: "active", urls: { customer_portal: "javascript:alert(1)" } } } }, Buffer.from("z"));
      assert.equal(sneaky.manageUrl, null);
    });

    it("lets an admin set the payment link, https only", async () => {
      assert.equal((await call("/v1/admin/settings", { method: "PUT", token: "operator", body: { paymentLink: "http://insecure.example/pay" } })).status, 400);
      const ok = await call("/v1/admin/settings", { method: "PUT", token: "operator", body: { paymentLink: "https://datarail.lemonsqueezy.com/buy/xyz", priceLabel: "$5/mo" } });
      assert.equal((await ok.json()).provider, "lemonsqueezy");
      const s = await (await call("/v1/admin/settings", { token: "operator" })).json();
      assert.equal(s.webhooks.stripe.configured, true);
      assert.ok(s.webhooks.stripe.lastMs > 0);
      await call("/v1/admin/settings", { method: "PUT", token: "operator", body: { paymentLink: "https://buy.stripe.com/test_abc", priceLabel: "$4 a month" } });
    });
  });

  describe("premium soundtracks", () => {
    const wav = (seconds) => pcmToWav(Buffer.alloc(Math.round(seconds * 8000) * 2), 8000);
    let blob;

    before(async () => {
      blob = await (await fetch(`${base}/v1/blobs?name=p.wav`, { method: "PUT", headers: { authorization: "Bearer operator" }, body: wav(3.21) })).json();
      const stems = mission.signals.map((s) => ({ id: s.stem, blob: blob.name }));
      const res = await call(`/v1/tracks/${mission.id}/members-mix`, {
        method: "PUT",
        token: "operator",
        body: { title: "Members Mix", premium: true, published: true, music: { bpm: mission.music.bpm, stems } },
      });
      assert.equal(res.status, 200);
      const bad = await call(`/v1/tracks/${mission.id}/members-bonus`, { method: "PUT", token: "operator", body: { title: "x", premium: true, unlockAfterCompletions: 2, music: { bpm: 90, stems: [] } } });
      assert.equal(bad.status, 400);
    });

    after(() => call(`/v1/tracks/${mission.id}/members-mix`, { method: "DELETE", token: "operator" }));

    const catalog = async (accountToken) =>
      (await fetch(`${base}/v1/tracks/${mission.id}`, { headers: { authorization: "Bearer event", ...(accountToken ? { "x-account-token": accountToken } : {}) } })).json();

    it("shows a free player the premium track as locked, and gives a premium player the track", async () => {
      const free = await catalog();
      assert.equal(free.soundtracks.some((t) => t.id === "members-mix"), false);
      assert.deepEqual(free.locked.map((t) => t.id), ["members-mix"]);
      assert.equal((await premiumOf2()).active, true);
      const paid = await catalog(player.token);
      assert.equal(paid.soundtracks.some((t) => t.id === "members-mix"), true);
      assert.deepEqual(paid.locked, []);
      const me = await (await acct("/v1/account/me", { token: player.token })).json();
      assert.deepEqual(me.premiumTracks.map((t) => t.title), ["Members Mix"]);
    });

    it("only hands premium stems to premium players and admins", async () => {
      const get = (headers) => fetch(`${base}/v1/blobs/${blob.name}`, { headers: { authorization: "Bearer event", ...headers } });
      assert.equal((await get({})).status, 403);
      assert.equal((await get({ "x-account-token": player.token })).status, 200);
      assert.equal((await fetch(`${base}/v1/blobs/${blob.name}`, { headers: { authorization: "Bearer operator" } })).status, 200);
    });

    const premiumOf2 = async () => (await (await acct("/v1/account/me", { token: player.token })).json()).account.premium;
  });

  it("deletes an account with its password", async () => {
    assert.equal((await acct("/v1/account/delete", { method: "POST", token: player.token, body: { password: "wrong" } })).status, 403);
    assert.equal((await acct("/v1/account/delete", { method: "POST", token: player.token, body: { password: "new password 1" } })).status, 204);
    assert.equal((await acct("/v1/account/me", { token: player.token })).status, 401);
    assert.equal((await acct("/v1/account/login", { method: "POST", body: { email: "runner@example.com", password: "new password 1" } })).status, 401);
  });
});

describe("web player", () => {
  it("serves the player, its engine and the game's own audio", async () => {
    const page = await fetch(`${base}/play/`);
    assert.equal(page.status, 200);
    assert.match(page.headers.get("content-security-policy"), /media-src 'self' blob:/);
    assert.match(page.headers.get("permissions-policy"), /geolocation=\(self\)/);
    const engine = await fetch(`${base}/play/engine/ghostsignal-core.mjs`, { headers: { "accept-encoding": "gzip" } });
    assert.equal(engine.status, 200);
    assert.equal(engine.headers.get("content-encoding"), "gzip");
    assert.match(engine.headers.get("content-type"), /javascript/);
    const again = await fetch(`${base}/play/engine/ghostsignal-core.mjs`, { headers: { "if-none-match": engine.headers.get("etag") } });
    assert.equal(again.status, 304);
    const stem = await fetch(`${base}/play/assets/music/01_kick.ogg`);
    assert.equal(stem.status, 200);
    assert.equal(stem.headers.get("content-type"), "audio/ogg");
    assert.equal((await fetch(`${base}/`, { redirect: "manual" })).headers.get("location"), "/play/");
  });

  it("serves nothing outside the player and its assets", async () => {
    for (const p of ["/play/assets/../mission/ghostsignal.json", "/play/assets/fonts/x.ogg", "/play/assets/music/.hidden.ogg", "/play/../src/index.js", "/play/engine/ghostsignal-core.mjs.map"]) {
      assert.notEqual((await fetch(base + p)).status, 200, p);
    }
  });

  it("lets anyone read what the player needs, and nothing else", async () => {
    const anon = (p, init = {}) => fetch(base + p, init);
    const m = await anon("/v1/mission");
    assert.equal(m.status, 200);
    assert.equal((await m.json()).id, mission.id);
    assert.equal((await anon(`/v1/tracks/${mission.id}`)).status, 200);
    assert.equal((await anon("/v1/broadcasts")).status, 200);
    assert.notEqual((await anon(`/v1/routes/${mission.id}`)).status, 401);
    assert.equal((await anon("/v1/npc", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(npcBody) })).status, 401);
    assert.equal((await anon(`/v1/routes/${mission.id}`, { method: "PUT", headers: { "content-type": "application/json" }, body: "{}" })).status, 401);
  });

  it("lets a signed-in player talk to the characters without the event token", async () => {
    const s = await (await fetch(`${base}/v1/account/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ email: "boss@example.com", password: "boss password" }) })).json();
    const ask = () => fetch(`${base}/v1/npc`, { method: "POST", headers: { "content-type": "application/json", "x-account-token": s.token }, body: JSON.stringify(npcBody) });
    assert.equal((await ask()).status, 200);
    // A player without the event token has a smaller allowance of the paid services.
    let limited = false;
    for (let i = 0; i < 14 && !limited; i++) limited = (await ask()).status === 429;
    assert.ok(limited, "per-account limit on character replies");
  });
});

describe("glasses", () => {
  const post = (p, token, body) => fetch(base + p, { method: "POST", headers: { "content-type": "application/json", "x-account-token": token }, body: JSON.stringify(body) });
  const get = async (p, token) => (await fetch(base + p, { headers: { "x-account-token": token } })).json();
  const signup = async (email) =>
    (await (await fetch(`${base}/v1/account/signup`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ email, password: "glasses pass 1" }) })).json()).token;
  const pair = (identifier, extra = {}) => ({ identifier, deviceType: "RAYBAN_META", name: "Lyn's Ray-Bans", firmware: "v21.0", ...extra });
  const admin = (p, init = {}) => call(p, { token: "operator", ...init });
  let a;
  let b;

  before(async () => {
    a = await signup("glasses-a@example.com");
    b = await signup("glasses-b@example.com");
    assert.ok(a && b, "both players signed up");
  });

  it("asks an admin before linked glasses make an account premium", async () => {
    assert.equal((await post("/v1/account/glasses", a, {})).status, 400);
    assert.equal((await post("/v1/account/glasses", a, pair("mock-1", { mock: true }))).status, 400);
    const res = await post("/v1/account/glasses", a, pair("RB-PAIR-0001"));
    assert.equal(res.status, 202);
    const { link } = await res.json();
    assert.equal(link.status, "pending");
    assert.match(link.fingerprint, /^[0-9A-F]{4}-[0-9A-F]{4}$/);
    assert.equal((await get("/v1/account/me", a)).account.premium.active, false);

    const { links } = await (await admin("/v1/admin/glasses")).json();
    const queued = links.find((l) => l.email === "glasses-a@example.com");
    assert.deepEqual([queued.status, queued.deviceType, queued.name], ["pending", "RAYBAN_META", "Lyn's Ray-Bans"]);
    assert.equal(JSON.stringify(links).includes("RB-PAIR-0001"), false, "the identifier itself is never shown or stored");
    assert.equal(fs.readFileSync(path.join(dataDir, "glasses.json"), "utf8").includes("RB-PAIR-0001"), false);

    assert.equal((await admin(`/v1/admin/glasses/${queued.id}`, { method: "PUT", body: { status: "approved" } })).status, 200);
    const me = await get("/v1/account/me", a);
    assert.deepEqual([me.account.premium.active, me.account.premium.source], [true, "glasses"]);
    assert.equal(me.glasses[0].status, "approved");
  });

  it("keeps one pair to one account", async () => {
    const res = await post("/v1/account/glasses", b, pair("RB-PAIR-0001"));
    assert.equal(res.status, 409);
    const { links } = await (await admin("/v1/admin/glasses")).json();
    const linkA = links.find((l) => l.email === "glasses-a@example.com");
    assert.equal((await admin(`/v1/admin/glasses/${linkA.id}`, { method: "PUT", body: { status: "revoked" } })).status, 200);
    assert.equal((await get("/v1/account/me", a)).account.premium.active, false, "revoking takes premium away");
    assert.equal((await post("/v1/account/glasses", b, pair("RB-PAIR-0001"))).status, 202, "now b can ask for it");
    const after = await (await admin("/v1/admin/glasses")).json();
    assert.deepEqual(after.links.find((l) => l.email === "glasses-b@example.com").alsoRequestedBy, ["glasses-a@example.com"]);
  });

  it("links enrolled event glasses at once", async () => {
    const enrolled = await admin("/v1/admin/glasses/enroll", { method: "POST", body: pair("EVENT-PAIR-07", { note: "Event box, pair 7" }) });
    assert.equal(enrolled.status, 201);
    assert.equal((await post("/v1/account/glasses", b, pair("EVENT-PAIR-07"))).status, 200);
    assert.equal((await get("/v1/account/me", b)).account.premium.active, true);
    const { enrolled: pool } = await (await admin("/v1/admin/glasses")).json();
    assert.equal(pool[0].note, "Event box, pair 7");
    assert.equal((await call("/v1/admin/glasses/enroll", { method: "POST", token: "event", body: pair("X-1") })).status, 403);
  });

  it("lets a player unlink, and remembers a rejection", async () => {
    const mine = (await get("/v1/account/glasses", b)).glasses;
    const event = mine.find((l) => l.status === "approved");
    assert.equal((await fetch(`${base}/v1/account/glasses/${event.id}`, { method: "DELETE", headers: { "x-account-token": b } })).status, 204);
    assert.equal((await get("/v1/account/me", b)).account.premium.active, false);

    const { link } = await (await post("/v1/account/glasses", a, pair("SUSPICIOUS-9"))).json();
    await admin(`/v1/admin/glasses/${link.id}`, { method: "PUT", body: { status: "rejected" } });
    assert.equal((await post("/v1/account/glasses", a, pair("SUSPICIOUS-9"))).status, 403);
  });
});
