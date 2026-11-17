import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { DEFAULT_MISSION_ID, loadMission, loadMissions } from "../src/mission.js";
import { createNpc } from "../src/npc.js";
import { createServer } from "../src/server.js";
import { createStore } from "../src/store.js";
import { createStt } from "../src/stt.js";
import { createTts } from "../src/tts.js";

const mission = loadMission();
const second = structuredClone(mission);
second.id = "ghostsignal-test-second";
second.title = "Second Test Mission";

function fakeMetaFetch() {
  const fetchImpl = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ choices: [{ message: { content: "On it, runner." } }] }),
    text: async () => "",
  });
  return fetchImpl;
}

let server;
let base;

before(async () => {
  const onDisk = loadMissions();
  const all = [...onDisk.values()];
  if (!all.some((candidate) => candidate.id === second.id)) all.push(second);
  server = createServer({
    mission,
    missions: all,
    npc: createNpc({ apiKey: "test-key", fetchImpl: fakeMetaFetch(), log: { warn() {} } }),
    tts: createTts(),
    stt: createStt({ meta: { enabled: true, transcribe: async () => "decode" } }),
    store: createStore(fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-missions-"))),
    eventToken: "event",
    operatorToken: "operator",
    log: { warn() {}, error() {}, info() {} },
  });
  await new Promise((resolve) => server.listen(0, resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const call = (urlPath, { method = "GET", token = "event", body } = {}) =>
  fetch(base + urlPath, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

describe("mission registry files", () => {
  it("loads every mission JSON in a directory, keyed by id", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-reg-"));
    const a = { ...structuredClone(mission), id: "test-alpha" };
    const b = { ...structuredClone(mission), id: "test-beta" };
    fs.writeFileSync(path.join(dir, "a.json"), JSON.stringify(a));
    fs.writeFileSync(path.join(dir, "b.json"), JSON.stringify(b));
    fs.writeFileSync(path.join(dir, "notes.txt"), "not a mission");
    fs.writeFileSync(path.join(dir, "broken.json"), "{nope");
    const registry = loadMissions(dir);
    assert.deepEqual([...registry.keys()].sort(), ["test-alpha", "test-beta"]);
    assert.equal(registry.get("test-alpha").title, mission.title);
  });

  it("finds the default mission in the shipped assets", () => {
    const registry = loadMissions();
    assert.ok(registry.has(DEFAULT_MISSION_ID), "default mission is registered");
    assert.equal(registry.get(DEFAULT_MISSION_ID).id, mission.id);
    assert.equal(loadMission().id, DEFAULT_MISSION_ID, "loadMission stays back-compatible");
  });
});

describe("mission list and selection", () => {
  it("lists every registered mission with the default first, without a token", async () => {
    const res = await fetch(`${base}/v1/missions`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.defaultMissionId, DEFAULT_MISSION_ID);
    const ids = body.missions.map((entry) => entry.id);
    assert.equal(ids[0], DEFAULT_MISSION_ID);
    assert.ok(ids.includes(mission.id));
    assert.ok(ids.includes(second.id));
    for (const entry of body.missions) assert.equal(typeof entry.title, "string");
    // Whatever mission files ship in the repo must all be listed.
    for (const id of loadMissions().keys()) assert.ok(ids.includes(id), id);
  });

  it("serves the default mission with no query, for back-compat", async () => {
    const anon = await fetch(`${base}/v1/mission`);
    assert.equal(anon.status, 200);
    assert.equal((await anon.json()).id, DEFAULT_MISSION_ID);
  });

  it("selects a mission by missionId and by mission", async () => {
    for (const query of [`missionId=${second.id}`, `mission=${second.id}`]) {
      const res = await fetch(`${base}/v1/mission?${query}`);
      assert.equal(res.status, 200, query);
      assert.equal((await res.json()).id, second.id);
    }
    const byDefault = await fetch(`${base}/v1/mission?missionId=${DEFAULT_MISSION_ID}`);
    assert.equal((await byDefault.json()).id, DEFAULT_MISSION_ID);
  });

  it("404s an unknown mission id as JSON", async () => {
    for (const query of ["missionId=nope", "mission=nope"]) {
      const res = await fetch(`${base}/v1/mission?${query}`);
      assert.equal(res.status, 404, query);
      assert.match((await res.json()).error, /unknown mission/);
    }
  });

  it("selects the Moynihan mission when its file ships", async () => {
    if (!loadMissions().has("ghostsignal-moynihan")) return; // file lands separately
    const res = await fetch(`${base}/v1/mission?missionId=ghostsignal-moynihan`);
    assert.equal(res.status, 200);
    assert.equal((await res.json()).id, "ghostsignal-moynihan");
    const { missions } = await (await fetch(`${base}/v1/missions`)).json();
    assert.ok(missions.some((entry) => entry.id === "ghostsignal-moynihan"));
  });
});

describe("per-mission NPC, TTS and track logic", () => {
  const npcBody = {
    characterId: "vesper",
    utterance: "Which way do I go?",
    context: { phase: "HUNTING", collected: 0, total: 8 },
    history: [],
  };

  it("answers NPC requests against the selected mission", async () => {
    const res = await call("/v1/npc", { method: "POST", body: { ...npcBody, missionId: second.id } });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).characterId, "vesper");
    const byQuery = await call(`/v1/npc?mission=${second.id}`, { method: "POST", body: npcBody });
    assert.equal(byQuery.status, 200);
  });

  it("404s NPC and TTS requests that name an unknown mission", async () => {
    const npc = await call("/v1/npc", { method: "POST", body: { ...npcBody, missionId: "nope" } });
    assert.equal(npc.status, 404);
    assert.match((await npc.json()).error, /unknown mission/);
    const tts = await call("/v1/tts", {
      method: "POST",
      body: { missionId: "nope", characterId: "vesper", text: "Hello." },
    });
    assert.equal(tts.status, 404);
    const known = await call("/v1/tts", {
      method: "POST",
      body: { missionId: second.id, characterId: "vesper", text: "Hello." },
    });
    assert.equal(known.status, 501, "known mission still reaches the device-voice stub");
  });

  it("checks broadcast speakers against the selected mission", async () => {
    const ok = await call("/v1/broadcasts", {
      method: "POST",
      token: "operator",
      body: { missionId: second.id, speaker: "vesper", text: "Second mission check-in." },
    });
    assert.equal(ok.status, 201);
    const unknown = await call("/v1/broadcasts", {
      method: "POST",
      token: "operator",
      body: { missionId: "nope", speaker: "vesper", text: "Hello?" },
    });
    assert.equal(unknown.status, 404);
  });

  it("edits routes and reads tracks per mission, 404ing unknown ones", async () => {
    const signalId = second.signals[0].id;
    const route = {
      missionId: second.id,
      placements: { [signalId]: { location: { lat: 40.75, lon: -73.99 }, radiusM: 45, lockRadiusM: 28 } },
    };
    const put = await call(`/v1/routes/${second.id}`, { method: "PUT", token: "operator", body: route });
    assert.equal(put.status, 204);
    const got = await (await call(`/v1/routes/${second.id}`)).json();
    assert.equal(got.placements[signalId].location.lat, 40.75);

    assert.equal((await call("/v1/routes/nope", { method: "PUT", token: "operator", body: { missionId: "nope" } })).status, 404);
    assert.equal((await call("/v1/tracks/nope")).status, 404);
    assert.equal((await call(`/v1/tracks/${second.id}`)).status, 200);
  });

  it("serves the admin mission summary for the selected mission", async () => {
    const res = await call(`/v1/admin/mission?missionId=${second.id}`, { token: "operator" });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).id, second.id);
    const unknown = await call("/v1/admin/mission?missionId=nope", { token: "operator" });
    assert.equal(unknown.status, 404);
  });
});
