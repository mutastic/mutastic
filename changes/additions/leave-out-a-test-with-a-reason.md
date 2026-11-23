- **A way past a test that fails on its own: `exclude-tests:` in `mutastic.yaml`, each with a
  reason, and `--exclude-tests=Class#method` for one method.** A test that fails with no mutant in
  still stops the run, and its message now ends in the way past it: list it under `exclude-tests:`
  with a `reason:`, or give `--exclude-tests` for one run, which now takes one method of a class,
  `PricingTest#bigOrders`, with every overload and row, as well as a whole class. The summary's
  `Left out` row names each test with its reason, the Score row ends in `· 1 test left out`, and
  `run-finished` lists them as `testsLeftOut`. A left-out test can only make the score fall: what
  only it reaches is not covered, and a run with other tests left out is never compared as the
  same code. The agent format never offers it, and `mutastic gate` holds a task that adds one, as
  a task that took a test away. See
  [Configuration](https://docs.mutastic.io/guides/configuration/#leaving-a-test-out).
