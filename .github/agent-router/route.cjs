/**
 * Which coding agent takes a task: Claude or Meta Wonder. Pure functions, so the GitHub
 * workflow (run.cjs) and the tests share them.
 *
 * A decision comes from the first of these that gives one:
 *   1. the issue form's "Route" field, when someone picked an agent;
 *   2. Muse Glimmer 30B, when a tuned Glimmer router is configured (glimmer.cjs);
 *   3. the issue form's "Area" field, mapped through rules.json;
 *   4. keywords in the title and body, per area in rules.json;
 *   5. rules.json's default agent.
 * Every decision the router makes, and every relabel a person makes after it, becomes a row
 * of Glimmer training data (trainingRow), in the chat format the Fireworks cookbook trains on.
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const OTHER_AREA = "Something else";

function loadRules(file = path.join(__dirname, "rules.json")) {
  const rules = JSON.parse(fs.readFileSync(file, "utf8"));
  for (const [area, agent] of Object.entries(rules.areas)) {
    if (!rules.agents[agent]) throw new Error(`rules.json: area "${area}" names unknown agent "${agent}"`);
  }
  if (!rules.agents[rules.defaultAgent]) throw new Error(`rules.json: unknown defaultAgent "${rules.defaultAgent}"`);
  rules.patterns = Object.entries(rules.keywords ?? {}).map(([area, source]) => {
    if (!(area in rules.areas)) throw new Error(`rules.json: keywords for unknown area "${area}"`);
    return { area, re: new RegExp(source, "gi") };
  });
  return rules;
}

/** The answers in an issue made from .github/ISSUE_TEMPLATE/task.yml ("### Label" then the value). */
function parseForm(body) {
  const fields = {};
  const parts = String(body ?? "").split(/^### +/m).slice(1);
  for (const part of parts) {
    const nl = part.indexOf("\n");
    const label = (nl === -1 ? part : part.slice(0, nl)).trim();
    const value = nl === -1 ? "" : part.slice(nl + 1).trim();
    fields[label] = value === "_No response_" ? "" : value;
  }
  return fields;
}

/** The agent an issue form's Route answer names, if it names one. */
function routeFromForm(fields, rules) {
  const answer = (fields.Route ?? "").trim().toLowerCase();
  return Object.keys(rules.agents).find((id) => answer === rules.agents[id].name.toLowerCase() || answer === id) ?? null;
}

/** The task as the router (and Glimmer) reads it: the title and body, without the Route answer. */
function taskText({ title, body }) {
  const withoutRoute = String(body ?? "").replace(/^### +Route\s*\n[\s\S]*?(?=^### |(?![\s\S]))/m, "");
  return `${String(title ?? "").trim()}\n\n${withoutRoute.trim()}`.trim();
}

/** The area with the most keyword hits, or null. Ties go to the area listed first in rules.json. */
function areaFromKeywords(text, rules) {
  let best = null;
  for (const { area, re } of rules.patterns) {
    const hits = [...String(text).matchAll(re)].map((m) => m[0].toLowerCase());
    if (hits.length && (!best || hits.length > best.hits.length)) best = { area, hits };
  }
  return best;
}

/**
 * The routing decision for an issue: { agent, area, by, reason }. `glimmer` is optional; when
 * given and it answers with a valid route, its answer wins over the rules.
 */
async function decide(issue, { rules, glimmer = null }) {
  const fields = parseForm(issue.body);
  const formArea = fields.Area && fields.Area in rules.areas ? fields.Area : null;
  const keywordGuess = areaFromKeywords(taskText(issue), rules);
  const area = formArea ?? keywordGuess?.area ?? OTHER_AREA;

  const chosen = routeFromForm(fields, rules);
  if (chosen) return { agent: chosen, area, by: "form", reason: `chosen in the issue form` };

  if (glimmer?.enabled) {
    const answer = await glimmer.classify(taskText(issue)).catch(() => null);
    const valid = answer && rules.agents[answer.route];
    if (valid) {
      const glimmerArea = answer.area in rules.areas ? answer.area : area;
      return { agent: answer.route, area: glimmerArea, by: "glimmer", reason: `Muse Glimmer routed it (area: ${glimmerArea})` };
    }
  }
  if (formArea) return { agent: rules.areas[formArea], area, by: "area", reason: `area "${formArea}"` };
  if (keywordGuess) {
    const words = [...new Set(keywordGuess.hits)].slice(0, 4).join(", ");
    return { agent: rules.areas[keywordGuess.area], area, by: "keywords", reason: `looks like "${keywordGuess.area}" (${words})` };
  }
  return { agent: rules.defaultAgent, area, by: "default", reason: "nothing pointed elsewhere, so the default agent" };
}

/** The agent whose label an issue or PR carries, if exactly one. */
function agentFromLabels(labels, rules) {
  const names = (labels ?? []).map((l) => (typeof l === "string" ? l : l.name));
  const found = Object.keys(rules.agents).filter((id) => names.includes(rules.agents[id].label));
  return found.length === 1 ? found[0] : null;
}

/** The agent that opened a PR, from its branch name (claude/..., wonder/...). */
function agentFromBranch(branch, rules) {
  return Object.keys(rules.agents).find((id) => String(branch ?? "").startsWith(rules.agents[id].branchPrefix)) ?? null;
}

function otherAgent(agent, rules) {
  return Object.keys(rules.agents).find((id) => id !== agent) ?? null;
}

/** Issue numbers a PR body closes ("Closes #12", "fixes #3", "Resolves #40"). */
function linkedIssues(body) {
  return [...String(body ?? "").matchAll(/\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#(\d+)/gi)].map((m) => Number(m[1]));
}

/** What a PR from an agent must say before it can merge: the PR template's handoff headings. */
const HANDOFF_HEADINGS = ["What changed", "Verified", "Not verified"];
function missingHandoff(body) {
  const text = String(body ?? "");
  return HANDOFF_HEADINGS.filter((h) => {
    const m = text.match(new RegExp(`^#{2,3} +${h}\\s*$([\\s\\S]*?)(?=^#{2,3} |(?![\\s\\S]))`, "mi"));
    return !m || !m[1].replace(/<!--[\s\S]*?-->/g, "").trim();
  });
}

/**
 * Glimmer's instructions: the answer's shape, never the policy. As in the Fireworks cookbook's
 * prompt router, the tuned model learns the policy from the examples, so this stays the same in
 * training and at inference.
 */
function glimmerSystem(rules) {
  const areas = [...Object.keys(rules.areas), OTHER_AREA].map((a) => JSON.stringify(a)).join(", ");
  const agents = Object.keys(rules.agents).map((a) => JSON.stringify(a)).join(" or ");
  return [
    "You route development tasks for the GhostSignal V2 repository to one of two coding agents.",
    "Read the task and answer with one JSON object and nothing else:",
    `{"area": one of [${areas}], "route": ${agents}}`,
  ].join("\n");
}

/** One training example for Glimmer: the task, and the agent it went to in the end. */
function trainingRow(issue, { agent, area }, rules) {
  return {
    messages: [
      { role: "system", content: glimmerSystem(rules) },
      { role: "user", content: taskText(issue) },
      { role: "assistant", content: JSON.stringify({ area, route: agent }) },
    ],
  };
}

/** Held out of training for the eval: about one issue in ten, the same ones every export. */
function isHoldout(number) {
  return Number(number) % 10 === 0;
}

module.exports = {
  OTHER_AREA,
  HANDOFF_HEADINGS,
  loadRules,
  parseForm,
  routeFromForm,
  taskText,
  areaFromKeywords,
  decide,
  agentFromLabels,
  agentFromBranch,
  otherAgent,
  linkedIssues,
  missingHandoff,
  glimmerSystem,
  trainingRow,
  isHoldout,
};
