# The agent router

GhostSignal V2 is built by two coding agents, Claude and Meta Wonder. The router decides which
of them takes each task, puts the result in front of the other for review, and records every
decision as training data for **Muse Glimmer 30B**. The aim is for Glimmer to make the call
once it has learned to make it well. This follows the Fireworks workshop's advice: don't pick a
model, build a router. The workshop routes between a small and a big model; here the router
routes tasks between two agents.

The protocol the agents follow is in [AGENTS.md](../AGENTS.md). This page covers how routing
works and how to train Glimmer on it.

## How a task is routed

When an issue is opened without an `agent:` label, the router
([.github/agent-router](../.github/agent-router), run by
[.github/workflows/agent-router.yml](../.github/workflows/agent-router.yml)) takes the first of
these that gives an answer:

1. **A person's pick:** the Task form's Route field, set to Claude or Meta Wonder.
2. **Muse Glimmer**, when a tuned Glimmer is deployed and configured (below). If it's down or
   answers nonsense, the router moves on.
3. **The area** picked in the form, mapped to an agent in
   [rules.json](../.github/agent-router/rules.json).
4. **Keywords** in the title and body, per area, also in rules.json.
5. **The default agent** in rules.json: Meta Wonder, since V2 showcases the Meta stack.

It labels the issue `agent:<who>` and `status:ready`, and comments with the reason. Swapping the
label afterwards is an override: the router keeps one agent label and notes the change.

To change the policy, edit `rules.json`: which agent owns each area, the keywords, the default.
`node --test .github/agent-router/route.test.cjs` checks it still loads and routes.

Pull requests from `claude/...` or `wonder/...` branches get `by:<author>` and
`review:<the other agent>`, and the issues they close move to `status:review`. The PR's check
fails until its handoff sections (What changed, Verified, Not verified) are filled in.

## Training Muse Glimmer to route

Glimmer left Fireworks' serverless inference in September. It's a training model now: you tune
it, deploy the tuned version, and point the router at it. Until then, the rules route.

**1. Collect.** Route issues as usual. Each routed issue is one example, labelled with the agent
it ended up with, so an override teaches Glimmer something the rules got wrong. A few dozen
issues make a start, but the workshop's router trained on 560, so expect Glimmer to beat the
rules only once there are a few hundred.

**2. Export.** Actions → **Agent router** → **Run workflow**. The run uploads
`glimmer-router-data`, containing:
- `train.jsonl` and `holdout.jsonl`, one `{"messages": [system, user, assistant]}` row per issue,
  the chat format the cookbook trains on;
- about one issue in ten held out, always the same ones, so the eval never sees training rows.

The run's summary shows how often the rules pick the right agent on the held-out issues.

**3. Train.** Use the Fireworks cookbook's `muse_glimmer_train_router` notebook
(github.com/fw-ai/cookbook):
- base model `accounts/fireworks/models/muse-glimmer-30b`, LoRA SFT, pointed at `train.jsonl`;
- the system prompt in the export gives the answer's shape but not the policy, as in the
  cookbook, so the model learns the policy from the examples;
- per the workshop, LoRA SFT jobs started before 2026-12-01 00:00 PT are free, and the eval
  samples are billed.

**4. Deploy and connect.** Deploy the tuned model on Fireworks (an on-demand deployment, billed
while it runs). In the repo's Settings → Secrets and variables → Actions, add:
- the secret `FIREWORKS_API_KEY`;
- the variable `GLIMMER_MODEL`, set to the deployment path
  (`accounts/<account>/deployments/<name>`).

**5. Compare.** Run the workflow again. With Glimmer configured, the summary shows the rules and
Glimmer side by side on the held-out issues. Keep Glimmer only if it picks better; to switch it
off, delete the `GLIMMER_MODEL` variable. Routing carries on by the rules whenever Glimmer is
unset or unreachable.

## Files

| File | What it is |
| --- | --- |
| `.github/agent-router/rules.json` | The policy: areas, keywords, the default agent |
| `.github/agent-router/route.cjs` | Routing decisions, the issue-form reader, the training-row format |
| `.github/agent-router/glimmer.cjs` | The Fireworks call to a deployed Glimmer |
| `.github/agent-router/run.cjs` | The GitHub side: labels, comments, PR checks, export and eval |
| `.github/agent-router/route.test.cjs` | Tests, run by CI |
| `.github/ISSUE_TEMPLATE/task.yml` | The Task form |
| `.github/pull_request_template.md` | The handoff every agent PR fills in |
