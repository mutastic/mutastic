- **Maven runs test with assertions on, as Surefire does.** Surefire runs tests with `-ea` unless
  `enableAssertions` is false, but mutastic's test workers ran without it. So a test that checks an
  `assert` failed with no mutant in, and the run stopped, and code that only an `assert` calls had
  mutants no test could catch. Now the workers run with assertions on unless the build turns them
  off, as Gradle's runs already did. A score can rise where an `assert` catches a mutant.
