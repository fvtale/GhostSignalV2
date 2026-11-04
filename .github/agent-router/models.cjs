/**
 * The router's models, on Fireworks (OpenAI-shaped chat API, FIREWORKS_API_KEY as a secret):
 *
 * - Muse Spark Open makes the call today. It's an untuned model, so its system prompt spells
 *   out the policy in rules.json (sparkSystem), and it answers at low reasoning effort, the
 *   setting that scored best on easy tasks in the Fireworks workshop. On by default whenever
 *   FIREWORKS_API_KEY is set; SPARK_MODEL picks another model, "off" turns it off.
 * - Muse Glimmer 30B takes over once it's tuned on the routed issues (`run.cjs export`, then the
 *   cookbook's Glimmer starter) and deployed: GLIMMER_MODEL is the deployment's path
 *   (accounts/<account>/deployments/<id>). Its prompt is the answer's shape only, as in training.
 *
 * Either one answering nonsense, or not at all, leaves the decision to the rules. Steps:
 * docs/AGENT-ROUTER.md.
 */
"use strict";

const { glimmerSystem, sparkSystem, TASK_CHARS } = require("./route.cjs");

const FIREWORKS = "https://api.fireworks.ai/inference/v1";
const SPARK_DEFAULT = "accounts/fireworks/models/muse-spark-open";

/**
 * The model's answer: the last JSON object with a "route" in the reply, after any thinking
 * the model wrote out. Reasoning can mention the schema itself, so the last object is the
 * answer, not the first.
 */
function answerJson(text) {
  const s = String(text ?? "").replace(/<think>[\s\S]*?<\/think>/g, "");
  let found = null;
  let start = s.indexOf("{");
  while (start !== -1) {
    let next = start + 1;
    for (let end = s.indexOf("}", start); end !== -1; end = s.indexOf("}", end + 1)) {
      try {
        const obj = JSON.parse(s.slice(start, end + 1));
        if (obj && typeof obj === "object" && "route" in obj) found = obj;
        next = end + 1; // a whole object: skip what's nested inside it
        break;
      } catch {
        // keep looking: this brace pair wasn't a whole object
      }
    }
    start = s.indexOf("{", next);
  }
  return found;
}

/**
 * Issue text is written by anyone (the repository is public), so the model's reason is
 * treated as untrusted before it's posted: one line, no links, mentions or markup, kept short.
 */
function cleanReason(text) {
  return String(text ?? "")
    .replace(/\s+/g, " ")
    .replace(/https?:\/\/\S+/gi, "")
    .replace(/[@`*_[\]()<>#|~]/g, "")
    .trim()
    .slice(0, 160);
}

function createModelRouter({ name, by, apiKey, model, system, extra = {}, baseUrl = FIREWORKS, fetchImpl = globalThis.fetch, timeoutMs = 30_000, log = console }) {
  return {
    name,
    by,
    model,
    enabled: Boolean(apiKey && model),

    /** { route, area, reason } from the model, or null when it can't give a usable answer. */
    async classify(task) {
      let res;
      try {
        res = await fetchImpl(`${baseUrl}/chat/completions`, {
          method: "POST",
          headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
          body: JSON.stringify({
            model,
            messages: [
              { role: "system", content: system },
              { role: "user", content: String(task).slice(0, TASK_CHARS) },
            ],
            ...extra,
          }),
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (e) {
        log.warn?.(`${name} unreachable: ${e.message}`);
        return null;
      }
      if (!res.ok) {
        log.warn?.(`${name} answered ${res.status}: ${(await res.text().catch(() => "")).slice(0, 200)}`);
        return null;
      }
      const data = await res.json().catch(() => null);
      const answer = answerJson(data?.choices?.[0]?.message?.content);
      if (!answer || typeof answer.route !== "string") {
        log.warn?.(`${name} gave no usable answer`);
        return null;
      }
      return {
        route: answer.route.trim().toLowerCase(),
        area: typeof answer.area === "string" ? answer.area : "",
        reason: cleanReason(answer.reason),
      };
    },
  };
}

/** Muse Glimmer 30B, once tuned and deployed: GLIMMER_MODEL is its deployment's path. */
function createGlimmer({ rules, apiKey = process.env.FIREWORKS_API_KEY ?? "", model = process.env.GLIMMER_MODEL ?? "", ...rest } = {}) {
  return createModelRouter({
    name: "Muse Glimmer",
    by: "glimmer",
    apiKey,
    model,
    system: glimmerSystem(rules),
    extra: { temperature: 0, max_tokens: 512 },
    ...rest,
  });
}

/** Muse Spark Open, routing now with the policy in its prompt. SPARK_MODEL=off turns it off. */
function createSpark({ rules, apiKey = process.env.FIREWORKS_API_KEY ?? "", model = process.env.SPARK_MODEL || SPARK_DEFAULT, ...rest } = {}) {
  return createModelRouter({
    name: "Muse Spark",
    by: "muse-spark",
    apiKey,
    model: model === "off" ? "" : model,
    system: sparkSystem(rules),
    // Room for its reasoning before the answer; low effort is the workshop's best on easy tasks.
    extra: { max_tokens: 2048, reasoning_effort: "low" },
    ...rest,
  });
}

module.exports = { createGlimmer, createSpark, createModelRouter, answerJson, cleanReason, SPARK_DEFAULT };
