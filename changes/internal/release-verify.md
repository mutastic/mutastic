- **A release is checked once it's out, and its candidate before it's tagged.**
  `release-verify.yml` resolves the CLI from the GitHub release, the Maven plugin from Central and
  the Gradle plugin through the Plugin Portal, in clean caches on Linux, macOS and Windows, and runs
  each on `samples/pricing`; `candidate-run.yml` runs the CLI built from the tree over a share of
  every sample, and fails on a crash, a run that can't run, or tests that fail without any mutant.
