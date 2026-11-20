- **A removed `join` with a time limit runs again.** It was skipped as `arid: only waits`, but
  without it the code reads what the thread does before it's done: the same race a removed
  `join()` with no limit shows, which ran. How long it waits stays arid. Code that joins with a
  time limit gets a mutant more, and its score can fall.
