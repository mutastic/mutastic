- **`mutants --operators` estimates what a set adds to a run's tests.** Going from the defaults to
  `stronger` can multiply the mutants, and nothing said what that would cost before a run paid for
  it. After a run, `mutastic mutants --operators=stronger` adds a line before the count, such as
  `estimate: 31 more mutants than defaults, up to 7.7s more of tests, from the run of 22 Nov 2026
  14:03 UTC`: the time the covering tests of each extra mutant took in that run's coverage pass,
  added up. It never guesses: with no run recorded, one of other code or other operators, or
  mutants where that run measured nothing, it says why there's no estimate. `mutants --estimate`
  runs every test once, with no mutant in, and estimates `defaults`, `stronger` and `all`. See
  [All operators](https://docs.mutastic.io/concepts/operators/#choosing-operators).
