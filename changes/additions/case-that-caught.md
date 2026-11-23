- **A parameterized test that catches a mutant names the case that did.** `verify` and `explain`
  say `caught by CalculatorTest#addsMixed(int, int, int) (case [2] 2, 3, 5)`, and the
  `mutant-finished` event has `caughtCase`, with the method, the case's place in this run and its
  display name. A test that fails with no mutant in is named with its first failing case too, and
  once, where a method with two failing cases was named twice. The method is still the test that
  runs, counts and is remembered; the case is evidence of this run, as a row added above it moves
  it ([results](https://docs.mutastic.io/concepts/results/#parameterised-and-data-driven-tests)).
