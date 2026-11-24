- **Tests that can't start say why, not that a test called `System.exit`.** TestNG older than
  6.14.3, or JUnit 3 alone, stopped the coverage pass with `tests-stopped` and "A test that calls
  System.exit, or crashes the JVM, stops it". The exception that stopped them is now the headline,
  with what to change: upgrade TestNG to 6.14.3, or junit:junit to 4.13.2. The code is `no-tests`,
  exit 2, as nothing ran. See [Errors](https://docs.mutastic.io/reference/errors/#no-tests).
