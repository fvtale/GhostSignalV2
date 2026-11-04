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
it ended up with, so an override teaches Glimmer something the rules got wrong. The workshop's
router trained on 560 examples, and real issues will take a while to get there, so the export
also includes the seed examples below.

**Seed examples.** [seeds.jsonl](../.github/agent-router/seeds.jsonl) holds 219 example tasks:
- 73 are rephrased from work actually done on GhostSignal (`"from": "history"`); 146 are
  plausible upcoming V2 work (`"from": "planned"`).
- Each has an **area**, never an agent. The export gives it the agent that `rules.json` assigns
  to that area, so if you change who owns an area, every seed follows. "Something else" goes to
  the default agent.
- What Glimmer learns from them is **your policy applied to plain language**. For example, it
  learns that "the volume buttons do nothing during a run" is Android work, which the keyword
  rules miss. It does **not** learn which agent does better work, because nothing in the data
  says how a task turned out.
- Today the keyword rules send 167 of the 219 seeds (76%) to the right agent. The misses are
  mostly Android, deploy and router tasks described without the keywords, which fall through
  to the default agent. That's the gap Glimmer should close.
- One seed in ten is held out, like the issues, so the eval scores examples Glimmer never
  trained on.
- Real issues, and above all your overrides, are where Glimmer learns exceptions to the policy.
  Seeds can only teach it the policy as written.
- Edit the file freely: one JSON object per line, with `area`, `from` and `text` (title on the
  first line). `node --test .github/agent-router/route.test.cjs` checks it.

**2. Export.** Actions → **Agent router** → **Run workflow**. The run uploads
`glimmer-router-data`, containing:
- `train.jsonl` and `holdout.jsonl`, one `{"messages": [system, user, assistant]}` row per issue,
  the chat format the cookbook trains on;
- about one issue in ten held out, always the same ones, so the eval never sees training rows.

The run's summary shows how often the rules pick the right agent on the held-out issues.

The export also has `glimmer.jsonl`: the held-out rows first, then the training rows. That's the
file for step 3, and the run's summary prints the exact commands for it.

**3. Train**, with the Glimmer starter from the Fireworks cookbook
([github.com/fw-ai/cookbook](https://github.com/fw-ai/cookbook),
`training/examples/muse_glimmer`). On your computer, with Python 3.11 or newer and a Fireworks
API key from <https://app.fireworks.ai/settings/users/api-keys>:

```bash
git clone --depth 1 https://github.com/fw-ai/cookbook && cd cookbook/training
python -m venv .venv
source .venv/bin/activate              # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -e .
# Put FIREWORKS_API_KEY=... in training/.env (gitignored). Never paste it into a chat.
python -m examples.sft.muse_glimmer_starter plan     # read-only: account, promotion, cost
```

Copy `glimmer.jsonl` from the export into `cookbook/training`. Then, with `N` the number of
held-out rows the summary reported:

```bash
python -m examples.sft.muse_glimmer_starter prepare --file glimmer.jsonl --n-eval N --run-id router-1
python -m examples.sft.muse_glimmer_starter launch --surface serverless --file glimmer.jsonl --n-eval N --run-id router-1
```

What each command does:
- **`prepare`** is local and free. It checks every row, holds out the first `N` (the same issues
  this repo holds out), and prints one example as Glimmer will see it.
- **`launch`** trains a LoRA adapter on `accounts/fireworks/models/muse-glimmer-30b`, then
  answers the held-out issues with the base and the tuned model.

Results go in `muse_starter_logs/router-1/summary.json`:
- exact match;
- accuracy for each field of the answer (`area` and `route`);
- a paired significance test, base vs tuned.

The tuned adapter is saved as `accounts/<account>/models/muse-starter-sl-router-1`.

Costs: training is free for LoRA jobs started before 2026-12-01 00:00 PT, but the held-out
answers are billed per token, and `launch` ends with a cost line. The export's system prompt
gives the answer's shape but not the policy, as in the cookbook's prompt router, so the tuned
model learns the policy from the examples. When you're done with a run,
`muse_glimmer_starter cleanup --run-id router-1` deletes what it made.

The cookbook also ships coding-agent skills for these steps (`skills/`, see its
`skills/GETTING-STARTED.md`). A coding agent running on the computer that holds the Fireworks
key can run the starter for you.

**4. Deploy and connect.** Muse's public serverless inference was retired on 2026-09-25, so the
tuned model needs a deployment to answer the router. That's an on-demand deployment of
`accounts/<account>/models/muse-starter-sl-router-1`, billed per GPU-hour while it's up. The
cookbook's `case-studies/sft_prompt_router/prompt_router_dedicated.ipynb` creates one with the
Fireworks SDK, and scales it to zero or deletes it afterwards. In this repo's Settings →
Secrets and variables → Actions, add:
- the secret `FIREWORKS_API_KEY`;
- the variable `GLIMMER_MODEL`, set to the deployment's path
  (`accounts/<account>/deployments/<id>`).

A deployment that's always up costs far more than routing a few issues a day needs. Turn it on
for a demo or a busy week, and delete `GLIMMER_MODEL` while it's off so the rules route.

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
