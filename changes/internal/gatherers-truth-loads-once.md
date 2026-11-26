- **`GatherersTruthTest` loads each mutant's class once, not once a pair of inputs.** Its check that
  each skip is what it says loaded the mutated class, and the fixture as written, again for each
  of 49 pairs of inputs, about 3 seconds each time a mutant of the engine reached it. It now
  loads each class once and keeps what it gives, as the other truth tests do, through
  `ClaimFixture.outcomesOf`. Over the 443 mutants of the engine that reach it, every status is
  the same, and the test takes 14 milliseconds a run rather than 3.3 seconds.
