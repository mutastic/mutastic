- **mutastic runs on a corpus of real projects every night.** The samples show one feature each,
  and the bugs that mattered turned up in real projects instead: a loop's slots read twice, a test
  helper in another package, a Maven wrapper older than 3.9.6. `corpus/corpus.yaml` pins 28
  projects and modules, each to a commit and a JDK, with what a healthy run does on it: a score,
  the refusal that's right today, or the known bug that stops it, which fails the night once it
  scores so its entry moves to score with the fix. `.github/workflows/corpus.yml` runs a share of
  each one's mutants nightly, and on a release or candidate by hand (`scripts/corpus-run.sh`,
  through `just corpus`), checks the reports and the agent format's head on each score, and
  tables the results on the run's page against the last good night's. CONTRIBUTING.md says how to
  add a project, and that each corpus failure becomes a sample or fixture in the pull request
  that fixes it.
