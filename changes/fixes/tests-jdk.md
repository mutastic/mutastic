- **Tests on a JDK older than 17 are refused, naming the JDK.** A JDK 8 or 11 test toolchain
  stopped mutastic's test worker, which runs on 17 or later, and read as "internal error: the test
  worker didn't start", exit 4. mutastic now says which JDK the tests run on before any starts, as
  `build-jdk`, exit 2. A JDK 8 named by its JRE's `java`, or by a release file that says
  `1.8.0_392`, is read as 8, where it was read as mutastic's own JDK. See
  [Errors](https://docs.mutastic.io/reference/errors/#build-jdk).
