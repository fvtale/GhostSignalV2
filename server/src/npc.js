import { findCharacter } from "./mission.js";
import { createMeta, DEFAULT_CHAT_MODEL } from "./meta.js";

// Muse Spark at low reasoning effort by default: chat-style replies don't need deep thinking,
// and low effort keeps the runner's wait short. NPC_MODEL can name any Model API chat model.
export const DEFAULT_MODEL = DEFAULT_CHAT_MODEL;

/** Lines used when the model returns nothing speakable, so the runner always hears something in-world. */
const STATIC_LINES = [
  "Static on the line, runner. Ask me something else.",
  "HUSH is crawling all over this frequency. Keep moving.",
];

/**
 * The system prompt depends only on the mission and the character, so it's identical across a
 * character's requests and caches well. Everything that changes per request goes in the user turn.
 */
export function systemPrompt(mission, character) {
  return [
    `You are ${character.name}, ${character.role}, a character in "${mission.title}", a live audio game played through smart glasses while walking around a real place.`,
    "",
    `The world: ${mission.world}`,
    "",
    `Who you are: ${character.persona}`,
    "",
    "How to answer:",
    "- Your words are turned into speech and played in the player's ear while they walk. Answer in one or two short sentences, at most 40 words. No stage directions, emoji, markdown or lists.",
    "- Stay in character and call the player \"runner\".",
    "- Use the game state you are given for directions and progress. Never invent places, distances or events that aren't in it, and never claim the runner has finished something they haven't.",
    "- Don't tell the runner the exact words that solve a challenge; the game gives its own hints.",
    "- Safety comes first: if the runner mentions traffic, crossing a street, feeling unwell or being lost, tell them to stop, say \"pause\", and look around before anything else.",
    "- Keep it PG-13. If the runner asks about things outside the game, answer briefly and steer back to the mission in character.",
  ].join("\n");
}

export function userTurn(body) {
  const c = body.context ?? {};
  const state = [
    `- Phase: ${c.phase ?? "unknown"}`,
    `- Current objective: ${c.objective ?? "unknown"}`,
    c.signalName ? `- Next signal: ${c.signalName}${c.place ? ` at ${c.place}` : ""}` : null,
    c.distance ? `- It is ${c.distance} from the runner${c.direction ? `, ${c.direction}` : ""}` : null,
    `- Fragments collected: ${c.collected ?? 0} of ${c.total ?? "?"}`,
    c.challenge ? `- Current challenge type: ${c.challenge}` : null,
    c.flags && Object.keys(c.flags).length ? `- Story choices so far: ${Object.entries(c.flags).map(([k, v]) => `${k}=${v}`).join(", ")}` : null,
  ].filter(Boolean);
  const history = (body.history ?? [])
    .slice(-8)
    .map((t) => `${t.role === "runner" ? "Runner" : "You"}: ${String(t.text).slice(0, 300)}`);
  return [
    "Game state:",
    ...state,
    "",
    history.length ? "Recent conversation:" : null,
    ...history,
    history.length ? "" : null,
    `The runner says: "${String(body.utterance).slice(0, 400)}"`,
  ]
    .filter((l) => l !== null)
    .join("\n");
}

/** Strips anything that would sound wrong when spoken. */
export function cleanReply(text) {
  return String(text)
    .replace(/[*_`#>]/g, "")
    .replace(/\s+/g, " ")
    .replace(/^["“]|["”]$/g, "")
    .trim()
    .slice(0, 320);
}

/**
 * Character replies from the Meta Model API (Muse Spark). One key — MODEL_API_KEY — also
 * powers Muse Voice transcription (see stt.js); voices themselves are rendered on the device.
 */
export function createNpc({
  meta = null,
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  model = process.env.NPC_MODEL || DEFAULT_MODEL,
  effort = process.env.NPC_EFFORT || "low",
  baseUrl = process.env.META_API_BASE || undefined,
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const client = meta ?? createMeta({ apiKey, ...(baseUrl ? { baseUrl } : {}), fetchImpl, log });
  let staticIndex = 0;

  return {
    enabled: client.enabled,
    model,

    async reply(mission, body) {
      const character = findCharacter(mission, body.characterId);
      if (!character) {
        const err = new Error(`unknown character ${body.characterId}`);
        err.status = 404;
        throw err;
      }
      if (!client.enabled) {
        const err = new Error("character agent not configured (set MODEL_API_KEY)");
        err.status = 503;
        throw err;
      }
      const reply = cleanReply(await client.chat({ model, system: systemPrompt(mission, character), user: userTurn(body), effort }));
      return reply || cleanReply(STATIC_LINES[staticIndex++ % STATIC_LINES.length]);
    },
  };
}

/** Upstream failures map to HTTP statuses the app can reason about. */
export function npcErrorStatus(error) {
  if (error?.status === 404 || error?.status === 429 || error?.status === 502 || error?.status === 503) return error.status;
  return 500;
}
