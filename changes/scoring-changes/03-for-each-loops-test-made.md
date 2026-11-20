- **A for-each loop's test made `!=` is skipped as equivalent (stronger).** javac counts the
  loop's index up from 0 to the array's length and no further, so `mutastic/comparison` changing
  `index < length` to `index != length` changes nothing, and no test could catch it. `stronger`
  runs have one mutant fewer to run for each such loop, and scores go up a little; no id changes.
