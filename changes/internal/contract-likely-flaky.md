- **The contract says what a likely-flaky test's catches count for: nothing.** Decision 27: in
  the run that names a test likely flaky, for any reason, the mutants it reached run again without
  it; `flaky-watch.tsv` remembers it, and later runs confirm each of its catches 3 more times until
  it has held for 3 runs. The events gain `run-finished`'s `watched`, `likelyFlaky[].rerun`, the
  `fails-alone` reason and `flakyCatchBy`; mutation.json gains `mutastic.flakyCatchBy`. N81 holds
  both editions as pending (#225).
