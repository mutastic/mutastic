- **The editions' contract says how property tests' seeds and inputs are handled.** Decision 25
  pins a run's random inputs where a framework takes a seed from outside the test, so a mutant only
  some inputs catch is caught in every run or in none, and names the frameworks it can't seed. The
  events schema gains `run-started`'s `propertyTests` and `mutant-finished`'s `caughtInput`, and
  the report schema `caughtInput`. The Rust edition has them first; the Java edition's are pending
  (N79).
