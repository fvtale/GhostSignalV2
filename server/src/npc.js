import { findCharacter } from "./mission.js";
import { createMeta, DEFAULT_CHAT_MODEL } from "./meta.js";

// Character replies come from Muse Spark through the Meta Model API. Low
// reasoning effort is the default: these are chat replies, and every extra
// second of thinking is a second the runner stands still listening to
// static. NPC_MODEL may name any chat model the key can reach.

export const DEFAULT_MODEL = DEFAULT_CHAT_MODEL;

// In-world fallbacks for an empty model reply, so the runner always hears
// a voice rather than silence.
const FALLBACK_LINES = [
  "Static on the line, runner. Ask me something else.",
  "HUSH is crawling all over this frequency. Keep moving.",
];

const ANSWER_RULES = [
  "- Your words are turned into speech and played in the player's ear while they walk. Answer in one or two short sentences, at most 40 words. No stage directions, emoji, markdown or lists.",
  "- Stay in character and call the player \"runner\".",
  "- Use the game state you are given for directions and progress. Never invent places, distances or events that aren't in it, and never claim the runner has finished something they haven't.",
  "- Don't tell the runner the exact words that solve a challenge; the game gives its own hints.",
  "- Safety comes first: if the runner mentions traffic, crossing a street, feeling unwell or being lost, tell them to stop, say \"pause\", and look around before anything else.",
  "- Keep it PG-13. If the runner asks about things outside the game, answer briefly and steer back to the mission in character.",
];

/**
 * Builds the system prompt for a character. It depends only on the mission
 * and the character, so it stays identical across a run and caches well;
 * everything that changes per request goes into the user turn instead.
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
    ...ANSWER_RULES,
  ].join("\n");
}

/** Builds the per-request user turn: game state, recent turns, the question. */
export function userTurn(body) {
  const context = body.context ?? {};

  const stateLines = [
    `- Phase: ${context.phase ?? "unknown"}`,
    `- Current objective: ${context.objective ?? "unknown"}`,
    context.signalName
      ? `- Next signal: ${context.signalName}${context.place ? ` at ${context.place}` : ""}`
      : null,
    context.distance
      ? `- It is ${context.distance} from the runner${context.direction ? `, ${context.direction}` : ""}`
      : null,
    `- Fragments collected: ${context.collected ?? 0} of ${context.total ?? "?"}`,
    context.challenge ? `- Current challenge type: ${context.challenge}` : null,
    context.flags && Object.keys(context.flags).length
      ? `- Story choices so far: ${Object.entries(context.flags).map(([k, v]) => `${k}=${v}`).join(", ")}`
      : null,
  ].filter(Boolean);

  const historyLines = (body.history ?? [])
    .slice(-8)
    .map((turn) => `${turn.role === "runner" ? "Runner" : "You"}: ${String(turn.text).slice(0, 300)}`);

  return [
    "Game state:",
    ...stateLines,
    "",
    historyLines.length ? "Recent conversation:" : null,
    ...historyLines,
    historyLines.length ? "" : null,
    `The runner says: "${String(body.utterance).slice(0, 400)}"`,
  ]
    .filter((line) => line !== null)
    .join("\n");
}

/** Strips markup and quoting that would sound wrong when spoken aloud. */
export function cleanReply(text) {
  return String(text)
    .replace(/[*_`#>]/g, "")
    .replace(/\s+/g, " ")
    .replace(/^["“]|["”]$/g, "")
    .trim()
    .slice(0, 320);
}

export function createNpc({
  meta = null,
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  model = process.env.NPC_MODEL || DEFAULT_MODEL,
  effort = process.env.NPC_EFFORT || "low",
  baseUrl = process.env.META_API_BASE || undefined,
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const client = meta ?? createMeta({
    apiKey,
    ...(baseUrl ? { baseUrl } : {}),
    fetchImpl,
    log,
  });
  let fallbackIndex = 0;

  return {
    enabled: client.enabled,
    model,

    async reply(mission, body) {
      const character = findCharacter(mission, body.characterId);
      if (!character) {
        throw Object.assign(new Error(`unknown character ${body.characterId}`), { status: 404 });
      }
      if (!client.enabled) {
        throw Object.assign(
          new Error("character agent not configured (set MODEL_API_KEY)"),
          { status: 503 },
        );
      }
      const raw = await client.chat({
        model,
        system: systemPrompt(mission, character),
        user: userTurn(body),
        effort,
      });
      const reply = cleanReply(raw);
      if (reply) return reply;
      return cleanReply(FALLBACK_LINES[fallbackIndex++ % FALLBACK_LINES.length]);
    },
  };
}

/** Maps an NPC failure to the HTTP status the clients reason about. */
export function npcErrorStatus(error) {
  const known = [404, 429, 502, 503];
  return known.includes(error?.status) ? error.status : 500;
}
