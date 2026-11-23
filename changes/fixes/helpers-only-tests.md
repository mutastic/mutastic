- **A module whose test classes are only helpers gets a report.** Test sources with a fixtures
  class and no test stopped the run with `no-tests`, as if tests had been there and not run. The
  module has no tests, so it's reported as one with no test classes is: every mutant not covered,
  and the summary's Tests row says none were found. Tests that are there and don't run still stop
  it.
