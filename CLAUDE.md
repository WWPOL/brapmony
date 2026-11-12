# Fork notes (WWPOL/brapmony)

This repository is WWPOL's fork of [y4my4my4m/harmony](https://github.com/y4my4my4m/harmony).
Some work goes upstream as PRs, to y4my4my4m/harmony and y4my4my4m/harmony-discord-bridge.
These notes come from the owner of this fork and from the upstream maintainer's feedback on
our PRs.

This file is fork-only: it must never be part of a pull request to upstream.

## Pull requests to upstream

### No AI references

The upstream maintainer doesn't want Claude, AI, agent or LLM text in contributions. For
anything sent to a y4my4my4m repo:

- No `Co-Authored-By: Claude …` trailer in any commit. This overrides the default
  attribution lines; strip them when rebasing or cherry-picking fork commits.
- No "Generated with Claude Code" footer, and no mention of Claude, AI, agents or LLMs, in
  commit messages, PR titles or bodies, code comments or docs.
- No `CLAUDE.md`, `.claude/` or other agent files in the diff.
- Branch from `upstream/master` (`git remote add upstream https://github.com/y4my4my4m/harmony.git`),
  never from this fork's `master`, so this file and other fork-only commits stay out.

Before pushing an upstream-bound branch, check:

```bash
git log upstream/master..HEAD --format=%B | grep -iwE 'claude|ai|llm|agents?|anthropic'  # must print nothing
git diff --name-only upstream/master..HEAD | grep -iE '(^|/)(CLAUDE\.md|\.claude/)'      # must print nothing
```

Pull requests that stay inside this fork may keep attribution.

### Shape and size

- **Discuss before building big features.** Bring larger or design-heavy changes (anything
  that copies a Discord feature, changes data models, or touches federation) to the
  maintainer first, on Harmony (https://har.mony.lol) or in an issue, before writing the PR.
  Don't copy Discord just because Discord has it: check it fits Harmony's federated model
  (for example, per-server profiles on a federated identity that also has public posts).
- **Group by feature, not by step.** One PR per feature. Related work goes in one PR, split
  into clean commits, not a series of PRs. For example, all the `/bridge sync-*` commands
  (emojis, stickers, sounds, profile) should have been one "sync" PR, not nine.
- **Keep each PR reviewable.** If a feature is too big to review in one sitting, cut its
  scope or ship it in independent, separately useful parts. Each part should still be one
  coherent feature.
- **No stacked or unrelated history.** Each PR contains only its own commits on top of
  `upstream/master`, so the maintainer can change, reorder or drop any one of them. Avoid
  PRs that depend on other open PRs. If one can't be avoided, say so in one line.
- **Concise descriptions.** Say what changed and why, how it was tested, and any migration
  or operator impact. No long essays, no repeating the diff.
- **Don't touch PRs that are already open** unless the maintainer asks. They may already be
  pulling the changes in.
- Follow CONTRIBUTING.md: Conventional Commits; lint, type-check and tests pass.

## Scale and performance

Design for instances with tens of thousands of users or more, not just our instance
(~100 users).

- Keep hot paths light. Lists such as `user_servers` load IDs first and fetch details only
  when needed; don't add joins or extra columns to them.
- Think about what a change costs per user, per message and per server: query count,
  indexes, RLS policy cost, Realtime fan-out, federation traffic, queue load.
- Paginate or batch anything that grows with users or history. No unbounded loops or
  fetches.
- For changes on hot paths (startup, message load, server lists), measure the cost (see
  BENCHMARKING.md) and give the numbers in the PR.

## Ease of use

Non-technical people also run servers and instances, and adoption depends on them.

- Good defaults; things should work without configuration where possible.
- Plain-language settings, labels, errors and docs. No unexplained jargon.
- Keep the number of settings, environment variables and setup steps small. Self-host
  changes should work through the existing `harmony` CLI and installer flow.
- Destructive or hard-to-undo actions (imports, syncs in "mirror" mode, migrations) need a
  clear explanation and a confirmation step.

## Security reports

Security issues go to upstream privately (GitHub private vulnerability reporting; see
`SECURITY.md`), never in a public issue or PR.

## Images

With the repository variable `PUBLISH_IMAGES=true`, `images.yml` publishes
`ghcr.io/wwpol/harmony-*`; a self-hosted instance runs them with
`HARMONY_REGISTRY=ghcr.io/wwpol` (see docs/self-host/update-backup.md, "Forks").
