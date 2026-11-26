- **mutastic's own run of the engine leaves `CardLinesTest` out.** It checks how reports map a
  mutant's lines over every truth fixture, the engine's scanning code reaches it only on the way,
  and it was about 38% of the Nightly engine shares' test time, enough to take each share past its
  30 minutes. `mutastic-engine/mutastic.yaml` leaves it out with that reason, and the build and CI
  still run it. Of the 1,235 mutants it caught, it was the only test to catch one, a `throw` a
  local guards read as an `assert`; `ExceptionTruthTest` now has that shape and catches it too.
