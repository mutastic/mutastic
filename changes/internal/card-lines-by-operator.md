- **`CardLinesTest` costs a mutant's run far less.** It checked every truth fixture's mutants under
  every operator in one test, so every mutant of the engine that reached it ran all 16,625 of them
  again: about 4 seconds a mutant, and the Nightly's engine shares ran past their 30 minutes. It
  is now a test for each group of operators, with a test that every operator is in one group, so a
  mutant in one operator's code runs only its group. Over 392 of the engine's mutants, every
  status is the same, and they run in 40.5 seconds rather than 1m50s.
