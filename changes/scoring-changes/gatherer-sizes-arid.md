- **How many calls `mapConcurrent` runs at once is arid.** A number given straight to
  `Gatherers.mapConcurrent` and changed to another above zero gives the same results in the same
  order, so it's skipped as `arid: only sets how many run at once`. Changed to `0`, it always
  throws as the gatherer is made, and counts, as a window's size does. The rule is
  `mutastic:gatherer-sizes`, Java only. See [Arid
  rules](https://docs.mutastic.io/reference/arid/#gatherer-sizes).
