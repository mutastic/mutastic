- **The editions' contract says how long a mutant's tests may run.** Decision 26 takes a mutant's
  limit from each covering test's own time, shared out between the tests that run side by side and
  never under 10 seconds, under a ceiling that `mutastic.yaml`'s new `timeout-ceiling:` sets. The
  mutastic.yaml schema gains the key, which only the Rust edition reads for now; the Java edition's
  side is pending (N80).
