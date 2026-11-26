- **proptastic's properties are seeded, and a card names the input that caught.** A project whose
  tests use proptastic, found by its entry point's class on the test classpath whatever its jar is
  called, has every test JVM given the run's seed as proptastic reads one (`0x0`), with its saved
  failures, events and corpus off, so a mutant's run neither reads nor writes the person's files. A
  mutant's run stops at the first counterexample, `proptastic.shrink=false`; `run --only`,
  `verify` and `explain` shrink it once, and a card says `caught by ListsTest#sortsAsTheJdkDoes
  with `xs = [0]``, read from proptastic's report, its stacked form too
  ([Property tests](https://docs.mutastic.io/concepts/results/#property-tests)).
