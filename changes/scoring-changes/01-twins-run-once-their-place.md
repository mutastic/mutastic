- **Twins run once, and their place counts once.** Two mutants at one place that always do the
  same now run as one: the other is skipped as `twin: same as <id>`, naming the one that runs, and
  is out of the score. A for-each loop over an array has three such pairs (`<` made `<=` and the
  test always true, `<` made `>=` and `!=`, the test always false and `<` made `>`), and a
  comparison returned as it is, `return a > b;`, two (the test always true and `return true`,
  always false and `return false`). No id changes, and each still works: `verify`, `run --only`
  and `explain` run the one it's the same as, `mutastic ignore` records the decision for that one,
  and an ignore of either holds for both. Runs have fewer mutants to run, and a score moves a
  little where a place was counted twice. Events give a twin's `sameAs` and the skip kind `twin`.
  The Rust edition has twins too. See
  [Twins](https://docs.mutastic.io/concepts/results/#twins).
