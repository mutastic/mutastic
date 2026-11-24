- **A release is a list of `just` recipes.** `just release-prepare X.Y.Z` makes the release commit
  on a branch (the changelog's section, and for `X.Y.0` the docs' Rust pin) and opens its pull
  request, through `scripts/release-prepare.sh`, whose `--dry-run` stops before the push.
  `release-check`, `candidate`, `release-verify` and `release-hold` wrap the scripts and workflows
  that were typed by hand; `release`, `release-portal` and `docs-redeploy` publish, are Brian's,
  and ask first. `docs-pin-rust`, `docs-stamp` and `docs-compare` name the docs' edition scripts.
  RELEASING.md opens with the release as these recipes in order, and AGENTS.md says which an agent
  may run. The `release` environment's approval stays what guards a publish: `just --yes` skips a
  recipe's question.
