- **A removed `join` with a time limit runs again.** It was skipped as `arid: only waits`, but
  without it the code reads what the thread does before it's done: the same race a removed
  `join()` with no limit shows, which ran. How long it waits counts too, as a timeout's does: see
  the sleep entry below. Code that joins with a time limit gets mutants more, and its score can
  fall.
