- **The agent format no longer offers `--exclude-tests`.** For a test that fails on its own, or one
  that fails from outside the code, it told the agent to run without the test. Leaving a test out
  may hide a broken one, so it's the person's call: the agent is told to find out whether the
  change or the test is wrong, then tell the person, or propose leaving it out under
  `exclude-tests:` with its reason. The hint for tests that fail on a JDK service the project
  provides no longer ends in `--exclude-tests` either: the line after it names the tests to leave
  out, outside the agent format.
