/**
 * Muse Glimmer 30B as the router's model, on Fireworks. Glimmer left Fireworks' serverless
 * inference in September and is trained, not called as-is: tune it on the export from
 * `run.cjs export` (LoRA SFT, as in the cookbook's muse_glimmer_train_router notebook), deploy
 * the tuned model, and give the workflow its path as GLIMMER_MODEL
 * (accounts/<account>/deployments/<name>) with FIREWORKS_API_KEY as a secret. Until then the
 * router routes by its rules. Fireworks' chat API is OpenAI-shaped.
 */
"use strict";

const { glimmerSystem } = require("./route.cjs");

/** The first JSON object in a reply, or null. Tuned routers answer with bare JSON; base models may not. */
function firstJson(text) {
  const s = String(text ?? "");
  for (let start = s.indexOf("{"); start !== -1; start = s.indexOf("{", start + 1)) {
    for (let end = s.indexOf("}", start); end !== -1; end = s.indexOf("}", end + 1)) {
      try {
        return JSON.parse(s.slice(start, end + 1));
      } catch {
        // keep looking: this brace pair wasn't a whole object
      }
    }
  }
  return null;
}

function createGlimmer({
  apiKey = process.env.FIREWORKS_API_KEY ?? "",
  model = process.env.GLIMMER_MODEL ?? "",
  rules,
  baseUrl = "https://api.fireworks.ai/inference/v1",
  fetchImpl = globalThis.fetch,
  timeoutMs = 30_000,
  log = console,
} = {}) {
  return {
    enabled: Boolean(apiKey && model),
    model,

    /** { route, area } from Glimmer, or null when it can't give a usable answer. */
    async classify(task) {
      let res;
      try {
        res = await fetchImpl(`${baseUrl}/chat/completions`, {
          method: "POST",
          headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
          body: JSON.stringify({
            model,
            messages: [
              { role: "system", content: glimmerSystem(rules) },
              { role: "user", content: String(task).slice(0, 6000) },
            ],
            temperature: 0,
            max_tokens: 512,
          }),
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (e) {
        log.warn?.(`glimmer unreachable: ${e.message}`);
        return null;
      }
      if (!res.ok) {
        log.warn?.(`glimmer answered ${res.status}: ${(await res.text().catch(() => "")).slice(0, 200)}`);
        return null;
      }
      const data = await res.json().catch(() => null);
      const answer = firstJson(data?.choices?.[0]?.message?.content);
      if (!answer || typeof answer.route !== "string") return null;
      return { route: answer.route, area: typeof answer.area === "string" ? answer.area : "" };
    },
  };
}

module.exports = { createGlimmer, firstJson };
