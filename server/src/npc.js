import Anthropic from "@anthropic-ai/sdk";
import { findCharacter } from "./mission.js";

// Claude Opus 5.5 at low effort by default: chat-style replies don't need deep thinking, and low
// effort keeps the runner's wait short. Set NPC_MODEL=claude-haiku-5-5 for the lowest latency.
export const DEFAULT_MODEL = "claude-opus-5-5";

// Models that accept the server-side refusal fallback in its "default" form.
const FALLBACK_MODELS = new Set(["claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"]);

const REPLY_SCHEMA = {
  type: "object",
  properties: {
    reply: { type: "string", description: "What the character says out loud. One or two short sentences." },
  },
  required: ["reply"],
  additionalProperties: false,
};

/** Lines used when the model declines or fails, so the runner always hears something in-world. */
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

export function createNpc({ client = null, model = process.env.NPC_MODEL || DEFAULT_MODEL, effort = process.env.NPC_EFFORT || "low", log = console } = {}) {
  const anthropic = client ?? (process.env.ANTHROPIC_API_KEY || process.env.ANTHROPIC_AUTH_TOKEN ? new Anthropic({ timeout: 15_000, maxRetries: 1 }) : null);
  let staticIndex = 0;

  return {
    enabled: anthropic !== null,
    model,

    async reply(mission, body) {
      const character = findCharacter(mission, body.characterId);
      if (!character) {
        const err = new Error(`unknown character ${body.characterId}`);
        err.status = 404;
        throw err;
      }
      if (!anthropic) {
        const err = new Error("character agent not configured (set ANTHROPIC_API_KEY)");
        err.status = 503;
        throw err;
      }

      const request = {
        model,
        max_tokens: 2048,
        cache_control: { type: "ephemeral" },
        system: systemPrompt(mission, character),
        messages: [{ role: "user", content: userTurn(body) }],
        output_config: { effort, format: { type: "json_schema", schema: REPLY_SCHEMA } },
      };
      if (FALLBACK_MODELS.has(model)) {
        request.betas = ["server-side-fallback-2026-07-01"];
        request.fallbacks = "default";
      }

      const response = await anthropic.beta.messages.create(request);
      if (response.stop_reason === "refusal") {
        log.warn?.(`npc refusal (${response.stop_details?.category ?? "unknown"}) for ${character.id}`);
        return cleanReply(STATIC_LINES[staticIndex++ % STATIC_LINES.length]);
      }
      const text = response.content.find((b) => b.type === "text")?.text ?? "";
      let reply = text;
      try {
        reply = JSON.parse(text).reply ?? text;
      } catch {
        // Fall through with the raw text; the schema makes this rare.
      }
      reply = cleanReply(reply);
      return reply || cleanReply(STATIC_LINES[staticIndex++ % STATIC_LINES.length]);
    },
  };
}

/** Typed SDK errors map to HTTP statuses the app can reason about. */
export function npcErrorStatus(error) {
  if (error?.status === 404 || error?.status === 503) return error.status;
  if (error instanceof Anthropic.RateLimitError) return 429;
  if (error instanceof Anthropic.APIConnectionError) return 502;
  if (error instanceof Anthropic.APIError) return 502;
  return 500;
}
