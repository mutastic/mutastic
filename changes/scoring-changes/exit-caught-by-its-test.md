- **A mutant that makes the code a test runs exit is caught by that test, or not caught.** A test
  that calls `main` in the tests' own JVM, where a mutant made `main` call `System.exit`, read as a
  timeout. It's now caught by that test, by the exit, when the status fails a build's test run (any
  but 0): same score, but the test is named. Status 0 passes Gradle's test task, so it's no catch,
  and the tests after it decide: such a mutant can now read as missed, and a score can go down.
  See [When the code a test runs
  exits](https://docs.mutastic.io/concepts/results/#when-the-code-a-test-runs-exits).
