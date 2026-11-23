- **A gatherer's size below one, and how many `mapConcurrent` runs at once, are arid.** A number
  given straight to `Gatherers.windowFixed`, `windowSliding` or `mapConcurrent` and changed to `0`
  or `-1` always throws as the gatherer is made, so any test that runs the line catches it: it's
  skipped as `arid: a gatherer's size below one always throws`. `mapConcurrent`'s number changed to
  another above zero gives the same results in the same order, and is skipped as `arid: only sets
  how many run at once`. A window's size above zero still counts. The rule is
  `mutastic:gatherer-sizes`, Java only. See [Arid
  rules](https://docs.mutastic.io/reference/arid/#gatherer-sizes).
