# GhostSignal V2

Two coding agents share this repository, Claude and Meta Wonder. Follow @AGENTS.md: it says how
to find work routed to you (label `agent:claude`), how to hand it back, how to review Meta
Wonder's pull requests (label `review:claude`), and the constraints every change keeps.

## Before a task, read

- [docs/HANDOFF.md](docs/HANDOFF.md): the current state, newest entry first.
- [docs/AGENT-ROUTER.md](docs/AGENT-ROUTER.md): how work is routed to you, and the policy.
- [docs/FIELD_TEST.md](docs/FIELD_TEST.md): the acceptance checklist a build for the glasses
  must satisfy.
- [docs/AUDIO.md](docs/AUDIO.md): the audio architecture.

## Verify

Run the checks under "Verify a build" in AGENTS.md that apply. There's no Android SDK in
Claude's cloud sandbox, so `./gradlew :app:assembleDebug` runs in CI; say so rather than
claiming it.

## End every task with a report

What changed, the commands you ran with their results, and what you did NOT verify. It's the
PR template's **What changed**, **Verified** and **Not verified**, and the same goes in the chat.
