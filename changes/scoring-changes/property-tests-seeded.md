- **Property tests draw the same inputs in every run.** A property test draws its inputs at random,
  so a mutant only some of them catch was caught in one run and missed in the next. mutastic now
  gives every test JVM of a run one seed, `0` unless the test task sets the framework's own system
  property: QuickTheories' `QT_SEED` and Kotest's `kotest.proptest.default.seed`. A mutant only some
  draws catch can change status once, to what seed `0` draws, and then stays. A mutant's run turns
  their shrinking off, as the first counterexample already catches it, so a mutant whose property
  ran out of time while shrinking is now caught by the property's failure. junit-quickcheck can't
  be seeded from outside its tests, and jqwik's tests run as ordinary tests: a run whose tests use
  either ends with a note on stderr, `note: property tests in junit-quickcheck run with their own
  seeds, which mutastic can't set: …`. See [Property
  tests](https://docs.mutastic.io/concepts/results/#property-tests).
