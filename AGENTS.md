# Working on GhostSignal V2: two agents

Two coding agents build this repository: **Claude** (Claude Code) and **Meta Wonder** (Muse).
The agent router decides who takes each task, and the other agent reviews the result. This file
is the protocol both follow; [docs/AGENT-ROUTER.md](docs/AGENT-ROUTER.md) explains the router
itself and how Muse Glimmer learns to run it.

| | Claude | Meta Wonder |
| --- | --- | --- |
| Your tasks | label `agent:claude` | label `agent:wonder` |
| Your branches | `claude/<issue>-<name>` | `wonder/<issue>-<name>` |
| PRs you review | label `review:claude` | label `review:wonder` |

A task moves `status:ready` → `status:in-progress` → `status:review` → closed by its PR.

## Find your work

Reviews first, then tasks, oldest first:

```bash
gh pr list --label review:wonder                          # PRs waiting on your review
gh issue list --label agent:wonder --label status:ready   # tasks routed to you
```

(Swap in `claude` for Claude. Without `gh`, filter the Issues and Pull requests tabs by label.)

## Take a task

1. Claim it: `gh issue edit <n> --remove-label status:ready --add-label status:in-progress`.
2. Branch from the latest `main` with your prefix, e.g. `wonder/12-stt-keywords`. The prefix is
   how the router knows who opened the PR. Never commit to the other agent's branch.
3. Read [docs/HANDOFF.md](docs/HANDOFF.md) first: the newest entry is the current state.

## Hand it back

1. Run the checks you can, and say which ones you couldn't:
   - `cd server && npm test`
   - `./gradlew -p core jvmTest`
   - `tools/check_web_engine.sh`, after any change under `core/`. It rebuilds
     `server/play/engine`; commit the rebuilt engine with your change, or CI fails.
   - `node --test .github/agent-router/route.test.cjs`, after any change to the router.
2. Add a dated entry at the top of `docs/HANDOFF.md`: what changed, what you verified, what you
   didn't, what's next.
3. Open a PR to `main` from the template: `Closes #<n>`, then **What changed**, **Verified** and
   **Not verified**. The router labels it `review:<other agent>` and moves the issue to
   `status:review`. Its check stays red until those three sections have content.

## Verify a build

Before a PR that touches the app, the server or the engine, check what applies:

| Check | Command | Notes |
| --- | --- | --- |
| Engine | `./gradlew -p core jvmTest` | |
| Server | `cd server && npm test` | Includes the `/health` contract below |
| App | `./gradlew :app:assembleDebug` | Needs the Android SDK; CI builds it on every push |
| On the glasses | [docs/FIELD_TEST.md](docs/FIELD_TEST.md) | The acceptance checklist for a build meant for the glasses; list the items you couldn't check under **Not verified** |

`/health` is a contract the app, the web player and the installer read. It always reports `npc`
(characters on), `stt` (speech transcription on) and `model` (the chat model in use), alongside
`ok`, `mission` and `tts`. Don't rename or drop those.

## Constraints

- **Meta-only runtime.** The game itself (server, app, web player) calls only Meta's models: Muse
  Spark and Muse Voice, through `MODEL_API_KEY`. No Anthropic SDK or Claude API calls in the
  product. The router's Glimmer runs on Fireworks, but that's development tooling, not the game.
- **The mission JSON is canon.** `app/src/main/assets/mission/ghostsignal.json` holds the story,
  characters, signals and soundtracks. Change them there, not in code.
- **Never hand-edit `server/play/engine/ghostsignal-core.mjs`.** Regenerate it from `core/` (the
  command is under **Hand it back**).
- **FMOD is optional.** Without the FMOD SDK in `third_party/fmod` the app builds with its own
  mixer; the SDK never goes in the repository.
- **APKs are staged by commit** at `/srv/datarail/store/ghostsignal/apks/` on the VPS. This isn't
  automated yet ([#8](https://github.com/fvtale/GhostSignalV2/issues/8)); until it is, the
  latest build is on the `android-latest` release.

## Review the other agent's PR

- Read the handoff, check out the branch, run the checks, and try what's under **Not verified**
  if you can.
- Approve, or request changes with specific lines and why. Don't push to their branch: suggest
  the fix, or open a follow-up issue.
- Merging is the owner's call unless they've said otherwise. CI must be green first: engine,
  server, Android build and the web engine check.

## New work

- Open an issue with the **Task** form (Issues → New issue → Task) and leave Route as "Router
  decides" unless there's a reason to pick. Found something outside your task? Open an issue
  rather than widening your PR.
- To move a task to the other agent, swap its `agent:` label. The router removes the old one and
  records the override, and overrides are what Glimmer learns from.

## Ground rules

- **This repository is public.** No keys, tokens or personal data in commits, issues or PRs.
  Local keys go in `server/.env` (ignored by git); production ones in `/etc/ghostsignalv2.env` on
  the VPS ([DEPLOY.md](DEPLOY.md)).
- One task per PR. `main` stays green.
- Don't rewrite history on `main` or on a branch that isn't yours.
