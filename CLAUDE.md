# Fork notes (WWPOL/brapmony)

This repository is WWPOL's fork of [y4my4my4m/harmony](https://github.com/y4my4my4m/harmony).
This file is fork-only: it must never be part of a pull request to upstream.

## Pull requests to upstream

The upstream author asked that contributions carry **no Claude attribution**. For
anything sent to y4my4my4m/harmony:

- No `Co-Authored-By: Claude …` trailer in any commit. This overrides the default
  attribution lines; strip them when rebasing or cherry-picking fork commits.
- No "Generated with Claude Code" footer, or other Claude mention, in the PR body.
- No Claude mentions in code comments or docs.
- Branch from `upstream/master` (`git remote add upstream https://github.com/y4my4my4m/harmony.git`),
  never from this fork's `master`, so this file and other fork-only commits stay out.

Before pushing an upstream-bound branch, check:

```bash
git log upstream/master..HEAD --format=%B | grep -i claude    # must print nothing
git diff --name-only upstream/master..HEAD | grep -x CLAUDE.md # must print nothing
```

Pull requests that stay inside this fork may keep attribution.

## Security reports

Security issues go to upstream privately (GitHub private vulnerability reporting; see
`SECURITY.md`), never in a public issue or PR.

## Images

With the repository variable `PUBLISH_IMAGES=true`, `images.yml` publishes
`ghcr.io/wwpol/harmony-*`; a self-hosted instance runs them with
`HARMONY_REGISTRY=ghcr.io/wwpol` (see docs/self-host/update-backup.md, "Forks").
