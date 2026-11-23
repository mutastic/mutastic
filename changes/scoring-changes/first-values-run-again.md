- **A variable's first value of `0` or `1` runs again (stronger).** `int total = 0;` or
  `for (int i = 0; …)` was skipped as `arid: a variable's first value of 0 or 1`, but where a
  total, a count or an index starts flows into what the code gives, and a test of the result
  catches a wrong start; the skip hid those catches and the misses beside them. Now
  `mutastic/constant-value` changes it like any other number, and one the code replaces before
  anything reads it is skipped as equivalent. `stronger` runs get more mutants, and scores move;
  no id changes. The Rust edition makes them too. See [First
  values](https://docs.mutastic.io/reference/arid/#first-values).
