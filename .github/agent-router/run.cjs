/**
 * The agent router's GitHub side (.github/workflows/agent-router.yml runs it):
 *
 *   node run.cjs                  handle the event in GITHUB_EVENT_PATH
 *   node run.cjs export <dir>     write Glimmer training data from the routed issues
 *   node run.cjs eval <rows>      score the router on held-out rows (correct pick %)
 *
 * Issues: a new issue without an agent label gets one (route.cjs decides), plus status:ready
 * and a comment saying why. Relabelling it to the other agent is an override: the old agent
 * label comes off, and the override is what Glimmer trains on.
 * Pull requests: a PR from claude/... or wonder/... is labelled by:<agent> and review:<the other>,
 * the issues it closes move to status:review, and its handoff (the PR template's headings) is
 * checked; the check fails until it's filled in.
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const R = require("./route.cjs");
const { createGlimmer } = require("./glimmer.cjs");

const MARKER = "<!-- agent-router";
const STATUS = {
  "status:ready": { color: "0E8A16", description: "Routed and waiting for its agent" },
  "status:in-progress": { color: "FBCA04", description: "An agent is working on it" },
  "status:review": { color: "5319E7", description: "A PR is open; the other agent reviews it" },
};
const AGENT_COLORS = { claude: "D97757", wonder: "0866FF" };

function labelSpecs(rules) {
  const specs = { ...STATUS };
  for (const [id, a] of Object.entries(rules.agents)) {
    const color = AGENT_COLORS[id] ?? "BFD4F2";
    specs[a.label] = { color, description: `Work routed to ${a.name}` };
    specs[`by:${id}`] = { color, description: `Pull request opened by ${a.name}` };
    specs[`review:${id}`] = { color, description: `${a.name} reviews this pull request` };
  }
  return specs;
}

function createApi({ token, repo, fetchImpl = globalThis.fetch }) {
  const base = `https://api.github.com/repos/${repo}`;
  async function call(method, url, body) {
    const res = await fetchImpl(url.startsWith("http") ? url : `${base}${url}`, {
      method,
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
        "x-github-api-version": "2022-11-28",
        ...(body ? { "content-type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 404 && method === "DELETE") return null; // already gone
    if (!res.ok) throw new Error(`${method} ${url}: ${res.status} ${(await res.text()).slice(0, 200)}`);
    return res.status === 204 ? null : { data: await res.json(), link: res.headers.get("link") ?? "" };
  }
  return {
    async ensureLabels(specs) {
      const have = new Set();
      for (let page = 1; ; page++) {
        const r = await call("GET", `/labels?per_page=100&page=${page}`);
        r.data.forEach((l) => have.add(l.name));
        if (r.data.length < 100) break;
      }
      for (const [name, spec] of Object.entries(specs)) {
        if (!have.has(name)) await call("POST", "/labels", { name, ...spec });
      }
    },
    addLabels: (n, labels) => call("POST", `/issues/${n}/labels`, { labels }),
    removeLabel: (n, label) => call("DELETE", `/issues/${n}/labels/${encodeURIComponent(label)}`),
    comment: (n, body) => call("POST", `/issues/${n}/comments`, { body }),
    async issues() {
      const all = [];
      for (let page = 1; ; page++) {
        const r = await call("GET", `/issues?state=all&per_page=100&page=${page}`);
        all.push(...r.data.filter((i) => !i.pull_request));
        if (r.data.length < 100) break;
      }
      return all;
    },
  };
}

const names = (labels) => (labels ?? []).map((l) => (typeof l === "string" ? l : l.name));

async function onIssue(event, { api, rules, glimmer, log }) {
  const issue = event.issue;
  const labels = names(issue.labels);
  const agent = R.agentFromLabels(labels, rules);

  if (event.action === "labeled") {
    // A person (or an agent) moved it: keep exactly one agent label, and note the override.
    const added = event.label?.name;
    const to = Object.keys(rules.agents).find((id) => rules.agents[id].label === added);
    if (!to) return;
    const stale = Object.keys(rules.agents).filter((id) => id !== to && labels.includes(rules.agents[id].label));
    if (!stale.length) return;
    for (const id of stale) await api.removeLabel(issue.number, rules.agents[id].label);
    if (!labels.some((l) => l.startsWith("status:"))) await api.addLabels(issue.number, ["status:ready"]);
    const by = event.sender?.login ? `@${event.sender.login}` : "someone";
    await api.comment(issue.number, `${MARKER} ${JSON.stringify({ override: to, from: stale })} -->\nRe-routed to **${rules.agents[to].name}** by ${by}. Glimmer learns from this.`);
    log.info?.(`#${issue.number}: re-routed to ${to}`);
    return;
  }

  if (!["opened", "reopened", "edited"].includes(event.action) || agent || issue.state === "closed") return;
  const d = await R.decide(issue, { rules, glimmer });
  const status = labels.some((l) => l.startsWith("status:")) ? [] : ["status:ready"];
  await api.addLabels(issue.number, [rules.agents[d.agent].label, ...status]);
  const pick = `${MARKER} ${JSON.stringify({ agent: d.agent, area: d.area, by: d.by })} -->`;
  await api.comment(
    issue.number,
    `${pick}\nRouted to **${rules.agents[d.agent].name}**: ${d.reason}.\n\n` +
      `To send it to the other agent, swap the \`${rules.agents[d.agent].label}\` label for \`${rules.agents[R.otherAgent(d.agent, rules)].label}\`. ` +
      `Pick-up and hand-back steps are in [AGENTS.md](${event.repository?.html_url ?? ""}/blob/HEAD/AGENTS.md).`,
  );
  log.info?.(`#${issue.number}: ${d.agent} (${d.by}: ${d.reason})`);
}

async function onPullRequest(event, { api, rules, log, summary }) {
  const pr = event.pull_request;
  const author = R.agentFromBranch(pr.head?.ref, rules);
  if (!author) {
    log.info?.(`PR #${pr.number} isn't from an agent branch (${pr.head?.ref}); nothing to do`);
    return 0;
  }
  const reviewer = R.otherAgent(author, rules);
  const labels = names(pr.labels);
  const want = [`by:${author}`, `review:${reviewer}`].filter((l) => !labels.includes(l));
  if (want.length) await api.addLabels(pr.number, want);
  if (["opened", "reopened", "edited", "ready_for_review"].includes(event.action)) {
    for (const n of R.linkedIssues(pr.body)) {
      await api.addLabels(n, ["status:review"]).catch((e) => log.warn?.(`#${n}: ${e.message}`));
      for (const l of ["status:ready", "status:in-progress"]) await api.removeLabel(n, l).catch(() => {});
    }
  }
  const missing = R.missingHandoff(pr.body);
  if (missing.length) {
    summary(`### Handoff missing\n\nPR #${pr.number} from ${rules.agents[author].name} needs these sections filled in: ${missing.map((m) => `**${m}**`).join(", ")}. They're in the PR template; ${rules.agents[reviewer].name} reviews from them.`);
    log.error?.(`PR #${pr.number}: handoff missing ${missing.join(", ")}`);
    return 1;
  }
  summary(`Handoff complete. ${rules.agents[reviewer].name} reviews PR #${pr.number}.`);
  return 0;
}

/**
 * Glimmer training rows: every routed issue, labelled with where it went in the end (override or
 * not), then the seed examples, labelled by their area's agent under rules.json.
 */
function exportRows(issues, rules, seeds = []) {
  const train = [];
  const holdout = [];
  seeds.forEach((seed, i) => {
    const row = R.trainingRow(R.seedIssue(seed), { agent: R.agentForArea(seed.area, rules), area: seed.area }, rules);
    (i % 10 === 9 ? holdout : train).push(row); // one in ten held out, the same ones every time
  });
  for (const issue of issues) {
    const agent = R.agentFromLabels(issue.labels, rules);
    if (!agent) continue;
    const fields = R.parseForm(issue.body);
    const area = fields.Area in rules.areas ? fields.Area : R.areaFromKeywords(R.taskText(issue), rules)?.area ?? R.OTHER_AREA;
    const row = R.trainingRow(issue, { agent, area }, rules);
    (R.isHoldout(issue.number) ? holdout : train).push(row);
  }
  return { train, holdout };
}

/** What to run next with the export, in the cookbook's Glimmer starter (docs/AGENT-ROUTER.md). */
function starterSteps({ dir, train, holdout, routed = 0, seeded = 0 }) {
  const lines = [
    `### Muse Glimmer training data`,
    "",
    `${train} training rows and ${holdout} held-out rows, in \`${dir}/\`: ${routed} from routed issues and ${seeded} seed examples.`,
  ];
  if (!holdout || !train) {
    lines.push("", "Not enough routed issues to train yet: the starter needs held-out and training rows (one issue in ten is held out).");
    return lines.join("\n");
  }
  lines.push(
    "",
    "Train with the Fireworks cookbook's Glimmer starter (free LoRA SFT for jobs started before 2026-12-01 00:00 PT), from `cookbook/training`:",
    "",
    "```",
    `python -m examples.sft.muse_glimmer_starter prepare --file glimmer.jsonl --n-eval ${holdout} --run-id <run>`,
    `python -m examples.sft.muse_glimmer_starter launch --surface serverless --file glimmer.jsonl --n-eval ${holdout} --run-id <run>`,
    "```",
    "",
    `\`--n-eval ${holdout}\` makes the starter hold out the same issues as the table below. A few hundred rows is where the cookbook expects a first signal.`,
  );
  return lines.join("\n");
}

/** Correct-pick rate of a router on held-out rows: { correct, total, misses }. */
async function evaluate(rows, route) {
  let correct = 0;
  const misses = [];
  for (const row of rows) {
    const task = row.messages.find((m) => m.role === "user").content;
    const want = JSON.parse(row.messages.find((m) => m.role === "assistant").content).route;
    const got = await route(task);
    if (got === want) correct++;
    else misses.push({ task: task.split("\n")[0].slice(0, 80), want, got });
  }
  return { correct, total: rows.length, misses };
}

async function main(argv = process.argv.slice(2), env = process.env, deps = {}) {
  const log = deps.log ?? { info: console.log, warn: console.warn, error: console.error };
  const rules = deps.rules ?? R.loadRules();
  const glimmer = deps.glimmer ?? createGlimmer({ rules, log });
  const api = deps.api ?? createApi({ token: env.GITHUB_TOKEN, repo: env.GITHUB_REPOSITORY });
  const summary = (text) => {
    if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `${text}\n`);
    log.info?.(text);
  };

  if (argv[0] === "export") {
    const dir = argv[1] ?? "router-data";
    const issues = await api.issues();
    const seeds = deps.seeds ?? R.loadSeeds(rules);
    const { train, holdout } = exportRows(issues, rules, seeds);
    fs.mkdirSync(dir, { recursive: true });
    const jsonl = (rows) => rows.map((r) => JSON.stringify(r)).join("\n") + (rows.length ? "\n" : "");
    fs.writeFileSync(path.join(dir, "train.jsonl"), jsonl(train));
    fs.writeFileSync(path.join(dir, "holdout.jsonl"), jsonl(holdout));
    // The cookbook's Glimmer starter holds out the first --n-eval rows of one file: put ours
    // first, so its base-vs-tuned eval runs on the same held-out issues as `eval` below.
    fs.writeFileSync(path.join(dir, "glimmer.jsonl"), jsonl([...holdout, ...train]));
    const routed = issues.filter((i) => R.agentFromLabels(i.labels, rules)).length;
    summary(starterSteps({ dir, train: train.length, holdout: holdout.length, routed, seeded: seeds.length }));
    return 0;
  }

  if (argv[0] === "eval") {
    const file = argv[1] ?? "router-data/holdout.jsonl";
    const rows = fs.existsSync(file) ? fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l)) : [];
    if (!rows.length) {
      summary("No held-out rows yet: the router needs more routed issues (one in ten is held out).");
      return 0;
    }
    const byRules = await evaluate(rows, async (task) => (await R.decide({ title: "", body: task }, { rules })).agent);
    const pct = (r) => `${Math.round((100 * r.correct) / r.total)}% (${r.correct}/${r.total})`;
    summary(`### Router on ${rows.length} held-out issues\n\n| Router | Correct pick |\n| --- | --- |\n| Rules | ${pct(byRules)} |`);
    if (glimmer.enabled) {
      const byGlimmer = await evaluate(rows, async (task) => (await glimmer.classify(task))?.route ?? null);
      summary(`| Muse Glimmer (${glimmer.model}) | ${pct(byGlimmer)} |`);
    }
    return 0;
  }

  const event = JSON.parse(fs.readFileSync(env.GITHUB_EVENT_PATH, "utf8"));
  await api.ensureLabels(labelSpecs(rules));
  if (env.GITHUB_EVENT_NAME === "issues") {
    await onIssue(event, { api, rules, glimmer, log });
    return 0;
  }
  if (env.GITHUB_EVENT_NAME === "pull_request") return onPullRequest(event, { api, rules, log, summary });
  log.info?.(`nothing to do for ${env.GITHUB_EVENT_NAME}`);
  return 0;
}

module.exports = { main, onIssue, onPullRequest, exportRows, evaluate, starterSteps, labelSpecs, createApi, MARKER };

if (require.main === module) {
  main().then(
    (code) => process.exit(code),
    (e) => {
      console.error(e);
      process.exit(1);
    },
  );
}
