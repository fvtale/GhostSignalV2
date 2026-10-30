"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { describe, it } = require("node:test");
const R = require("./route.cjs");
const { createGlimmer, firstJson } = require("./glimmer.cjs");
const { main, onIssue, onPullRequest, exportRows, evaluate } = require("./run.cjs");

const rules = R.loadRules();

/** An issue body the way GitHub renders .github/ISSUE_TEMPLATE/task.yml. */
const formBody = ({ goal = "Do the thing.", done = "It works.", area = "Something else", route = "Router decides", notes = "_No response_" } = {}) =>
  `### Goal\n\n${goal}\n\n### Done when\n\n${done}\n\n### Area\n\n${area}\n\n### Route\n\n${route}\n\n### Notes\n\n${notes}`;

const filledHandoff = "Closes #4\n\n## What changed\n\nThe thing.\n\n## Verified\n\nnpm test: 58 pass.\n\n## Not verified\n\nNothing.\n";

function fakeApi() {
  const calls = [];
  return {
    calls,
    ensureLabels: async () => {},
    addLabels: async (n, labels) => calls.push(["add", n, labels]),
    removeLabel: async (n, label) => calls.push(["remove", n, label]),
    comment: async (n, body) => calls.push(["comment", n, body]),
  };
}
const quiet = { info() {}, warn() {}, error() {} };

describe("reading the issue form", () => {
  it("reads each answer and treats _No response_ as empty", () => {
    const f = R.parseForm(formBody({ area: "Android app", route: "Claude" }));
    assert.equal(f.Area, "Android app");
    assert.equal(f.Route, "Claude");
    assert.equal(f.Notes, "");
  });

  it("leaves the Route answer out of what the router and Glimmer read", () => {
    const text = R.taskText({ title: "Fix STT", body: formBody({ route: "Meta Wonder" }) });
    assert.match(text, /^Fix STT/);
    assert.match(text, /### Area/);
    assert.doesNotMatch(text, /Route|Meta Wonder/);
  });
});

describe("deciding who takes a task", () => {
  it("follows an agent picked in the form", async () => {
    const d = await R.decide({ title: "Anything", body: formBody({ area: "Android app", route: "Meta Wonder" }) }, { rules });
    assert.deepEqual([d.agent, d.by], ["wonder", "form"]);
  });

  it("maps the form's area through rules.json", async () => {
    const d = await R.decide({ title: "Speed up the APK build", body: formBody({ area: "Deploy, CI and the VPS" }) }, { rules });
    assert.deepEqual([d.agent, d.by, d.area], ["claude", "area", "Deploy, CI and the VPS"]);
  });

  it("goes by keywords for a free-text issue", async () => {
    const d = await R.decide({ title: "Muse Voice mishears the passphrase", body: "Transcription drops the last word." }, { rules });
    assert.deepEqual([d.agent, d.by, d.area], ["wonder", "keywords", "Meta models, voice and characters"]);
    const c = await R.decide({ title: "Caddy reload fails on the VPS", body: "deploy step" }, { rules });
    assert.deepEqual([c.agent, c.by], ["claude", "keywords"]);
  });

  it("falls back to the default agent", async () => {
    const d = await R.decide({ title: "Tidy up", body: "" }, { rules });
    assert.deepEqual([d.agent, d.by, d.area], [rules.defaultAgent, "default", R.OTHER_AREA]);
  });

  it("lets a configured Glimmer decide ahead of the rules, but not ahead of a person's pick", async () => {
    const glimmer = { enabled: true, classify: async () => ({ route: "claude", area: "Web player" }) };
    const d = await R.decide({ title: "Muse Voice mishears", body: "" }, { rules, glimmer });
    assert.deepEqual([d.agent, d.by, d.area], ["claude", "glimmer", "Web player"]);
    const picked = await R.decide({ title: "x", body: formBody({ route: "Meta Wonder" }) }, { rules, glimmer });
    assert.equal(picked.by, "form");
  });

  it("falls back to the rules when Glimmer fails or answers nonsense", async () => {
    for (const classify of [async () => null, async () => ({ route: "gpt" }), async () => { throw new Error("down"); }]) {
      const d = await R.decide({ title: "Caddy reload fails", body: "" }, { rules, glimmer: { enabled: true, classify } });
      assert.deepEqual([d.agent, d.by], ["claude", "keywords"]);
    }
  });
});

describe("Muse Glimmer on Fireworks", () => {
  const reply = (content, status = 200) => async (url, init) => {
    reply.last = { url, body: JSON.parse(init.body), auth: init.headers.authorization };
    return new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status });
  };

  it("is off without a key and a model", () => {
    assert.equal(createGlimmer({ rules, apiKey: "", model: "m" }).enabled, false);
    assert.equal(createGlimmer({ rules, apiKey: "k", model: "" }).enabled, false);
  });

  it("asks the deployed model with the schema-only system prompt and reads its JSON", async () => {
    const g = createGlimmer({ rules, apiKey: "fw_test", model: "accounts/me/deployments/router", fetchImpl: reply('{"area":"Android app","route":"claude"}') });
    assert.deepEqual(await g.classify("Fix the APK"), { route: "claude", area: "Android app" });
    assert.equal(reply.last.url, "https://api.fireworks.ai/inference/v1/chat/completions");
    assert.equal(reply.last.body.model, "accounts/me/deployments/router");
    assert.equal(reply.last.auth, "Bearer fw_test");
    assert.equal(reply.last.body.messages[0].content, R.glimmerSystem(rules));
    const prompt = reply.last.body.messages[0].content;
    for (const [area, agent] of Object.entries(rules.areas)) {
      assert.ok(!prompt.includes(`"${area}": "${agent}"`) && !prompt.includes(`${area} -> ${agent}`), "the policy is learned, not in the prompt");
    }
    assert.doesNotMatch(prompt, /default/i);
  });

  it("finds the JSON after a base model's preamble, and gives up cleanly on errors", async () => {
    assert.deepEqual(firstJson('Sure! {"x":{"y":1}} done'), { x: { y: 1 } });
    const chatty = createGlimmer({ rules, apiKey: "k", model: "m", fetchImpl: reply('I think {"route":"wonder","area":"Web player"}') });
    assert.equal((await chatty.classify("t")).route, "wonder");
    assert.equal(await createGlimmer({ rules, apiKey: "k", model: "m", fetchImpl: reply("no json") }).classify("t"), null);
    assert.equal(await createGlimmer({ rules, apiKey: "k", model: "m", log: quiet, fetchImpl: reply("{}", 500) }).classify("t"), null);
    const down = async () => { throw new Error("ECONNREFUSED"); };
    assert.equal(await createGlimmer({ rules, apiKey: "k", model: "m", log: quiet, fetchImpl: down }).classify("t"), null);
  });
});

describe("issues on GitHub", () => {
  const issue = (extra = {}) => ({ number: 7, state: "open", title: "Caddy reload fails", body: "", labels: [], ...extra });

  it("labels a new issue, marks it ready and says why", async () => {
    const api = fakeApi();
    await onIssue({ action: "opened", issue: issue(), repository: { html_url: "https://github.com/fvtale/GhostSignalV2" } }, { api, rules, log: quiet });
    assert.deepEqual(api.calls[0], ["add", 7, ["agent:claude", "status:ready"]]);
    const [, , body] = api.calls[1];
    assert.match(body, /^<!-- agent-router \{"agent":"claude"/);
    assert.match(body, /Routed to \*\*Claude\*\*/);
    assert.match(body, /https:\/\/github\.com\/fvtale\/GhostSignalV2\/blob\/HEAD\/AGENTS\.md/);
  });

  it("leaves an issue that already has an agent alone", async () => {
    const api = fakeApi();
    await onIssue({ action: "edited", issue: issue({ labels: [{ name: "agent:wonder" }] }) }, { api, rules, log: quiet });
    assert.deepEqual(api.calls, []);
  });

  it("treats a relabel as an override: one agent label stays, and the change is noted", async () => {
    const api = fakeApi();
    const labels = [{ name: "agent:claude" }, { name: "agent:wonder" }, { name: "status:ready" }];
    await onIssue({ action: "labeled", label: { name: "agent:wonder" }, sender: { login: "fvtale" }, issue: issue({ labels }) }, { api, rules, log: quiet });
    assert.deepEqual(api.calls[0], ["remove", 7, "agent:claude"]);
    assert.match(api.calls[1][2], /"override":"wonder"/);
    assert.match(api.calls[1][2], /Re-routed to \*\*Meta Wonder\*\* by @fvtale/);
  });
});

describe("pull requests from the agents", () => {
  const pr = (extra = {}) => ({ number: 9, head: { ref: "claude/fix-caddy" }, body: filledHandoff, labels: [], ...extra });

  it("asks the other agent to review and moves the linked issue to review", async () => {
    const api = fakeApi();
    const code = await onPullRequest({ action: "opened", pull_request: pr() }, { api, rules, log: quiet, summary() {} });
    assert.equal(code, 0);
    assert.deepEqual(api.calls[0], ["add", 9, ["by:claude", "review:wonder"]]);
    assert.deepEqual(api.calls[1], ["add", 4, ["status:review"]]);
    assert.deepEqual(api.calls.slice(2).map((c) => c[2]), ["status:ready", "status:in-progress"]);
  });

  it("fails the check until the handoff is filled in", async () => {
    const template = fs.readFileSync(path.join(__dirname, "..", "pull_request_template.md"), "utf8");
    assert.deepEqual(R.missingHandoff(template), R.HANDOFF_HEADINGS, "the bare template is not a handoff");
    assert.deepEqual(R.missingHandoff(filledHandoff), []);
    let said = "";
    const code = await onPullRequest({ action: "opened", pull_request: pr({ head: { ref: "wonder/stt" }, body: template }) }, { api: fakeApi(), rules, log: quiet, summary: (t) => (said = t) });
    assert.equal(code, 1);
    assert.match(said, /Meta Wonder needs these sections filled in.*Claude reviews/s);
  });

  it("ignores branches that aren't an agent's", async () => {
    const api = fakeApi();
    assert.equal(await onPullRequest({ action: "opened", pull_request: pr({ head: { ref: "feature/x" } }) }, { api, rules, log: quiet, summary() {} }), 0);
    assert.deepEqual(api.calls, []);
  });

  it("reads which issues a PR closes", () => {
    assert.deepEqual(R.linkedIssues("Closes #3, fixes #12 and resolves #40. See #99."), [3, 12, 40]);
  });
});

describe("the GitHub API client", () => {
  const { createApi, labelSpecs } = require("./run.cjs");
  function fakeGitHub(existing) {
    const seen = [];
    const fetchImpl = async (url, init) => {
      seen.push([init.method, url.replace("https://api.github.com/repos/fvtale/GhostSignalV2", ""), init.body ? JSON.parse(init.body) : null, init.headers.authorization]);
      if (init.method === "GET") return new Response(JSON.stringify(existing.map((name) => ({ name }))), { status: 200 });
      if (init.method === "DELETE") return new Response("", { status: 404 });
      return new Response("{}", { status: 201 });
    };
    return { seen, api: createApi({ token: "ghs_test", repo: "fvtale/GhostSignalV2", fetchImpl }) };
  }

  it("creates only the labels that are missing, with the token", async () => {
    const { seen, api } = fakeGitHub(["agent:claude", "status:ready", "bug"]);
    await api.ensureLabels(labelSpecs(rules));
    const created = seen.filter(([m]) => m === "POST").map(([, , body]) => body.name);
    assert.ok(created.includes("agent:wonder") && created.includes("review:claude"));
    assert.ok(!created.includes("agent:claude") && !created.includes("status:ready"));
    assert.ok(seen.every(([, , , auth]) => auth === "Bearer ghs_test"));
  });

  it("treats removing a label that's already gone as done", async () => {
    const { seen, api } = fakeGitHub([]);
    assert.equal(await api.removeLabel(5, "status:ready"), null);
    assert.deepEqual(seen[0].slice(0, 2), ["DELETE", "/issues/5/labels/status%3Aready"]);
  });
});

describe("training data for Glimmer", () => {
  const issues = [
    { number: 1, title: "Fix the APK", body: formBody({ area: "Android app" }), labels: [{ name: "agent:claude" }] },
    { number: 2, title: "Muse Voice mishears", body: "", labels: [{ name: "agent:wonder" }] },
    { number: 10, title: "Caddy reload fails", body: "", labels: [{ name: "agent:wonder" }] }, // a person overrode the rules
    { number: 11, title: "Not routed yet", body: "", labels: [] },
  ];

  it("writes one chat row per routed issue, with the final agent, and holds out one in ten", () => {
    const { train, holdout } = exportRows(issues, rules);
    assert.equal(train.length, 2);
    assert.equal(holdout.length, 1);
    const row = train[0];
    assert.deepEqual(row.messages.map((m) => m.role), ["system", "user", "assistant"]);
    assert.deepEqual(JSON.parse(row.messages[2].content), { area: "Android app", route: "claude" });
    assert.deepEqual(JSON.parse(holdout[0].messages[2].content).route, "wonder", "the override is the label");
  });

  it("scores a router's picks on the held-out rows", async () => {
    const { holdout } = exportRows(issues, rules);
    const score = await evaluate(holdout, async () => "claude");
    assert.deepEqual([score.correct, score.total], [0, 1]);
    assert.equal(score.misses[0].want, "wonder");
  });

  it("exports through main() into train.jsonl and holdout.jsonl", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "router-data-"));
    const api = { ...fakeApi(), issues: async () => issues };
    const code = await main(["export", dir], {}, { api, rules, glimmer: { enabled: false }, log: quiet });
    assert.equal(code, 0);
    const lines = fs.readFileSync(path.join(dir, "train.jsonl"), "utf8").trim().split("\n");
    assert.equal(lines.length, 2);
    assert.ok(JSON.parse(lines[0]).messages);
    const evalCode = await main(["eval", path.join(dir, "holdout.jsonl")], {}, { api, rules, glimmer: { enabled: false }, log: quiet });
    assert.equal(evalCode, 0);
  });
});
