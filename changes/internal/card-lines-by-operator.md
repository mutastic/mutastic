- **The Nightly's engine shares finish inside their 30 minutes again.** `CardLinesTest` checked
  every truth fixture's mutants under every operator in one test, so every mutant of the engine
  that reached it ran all 16,625 of them again: about 4 seconds a mutant, enough to take each
  share past its limit. It is now a test for each group of operators, with a test that every
  operator is in one group, so a mutant in one operator's code runs only its group. Over the
  same 392 mutants, every status is the same and they run in 40.5 seconds, not 1m50s.
