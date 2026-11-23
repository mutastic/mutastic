- **A loop whose index was stored just before it no longer stops the run.** 1.3.0 checked every
  `while (i < n)` and `for` loop for the shape javac gives a for-each loop over an array, and when
  the store six instructions before the loop was to its index or its bound, the run stopped with
  `IllegalArgumentException: duplicate element`. Such a loop is now left as an ordinary loop, as it
  was before 1.3.0.
