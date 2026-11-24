- **CI runs the build through `just`.** Each job calls a recipe, `just ci-build`, `just test-cli
  2/4`, `just test-windows 1`, `just coverage-merge`, and the Nightly `just self-shard` and `just
  self-merge`, in place of Gradle commands and shell written into the workflows, so CI and a
  contributor type the same words and a job that fails can be run as CI ran it. Commits checks the
  justfile's format and its list of verbs, so none goes unseen.
