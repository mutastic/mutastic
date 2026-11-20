- **A test that runs `main` in a JVM of its own is said to reach no mutant.** A test that starts
  `java` on `java.class.path` to run the project's `main`, as a person would, found none of the
  project there and failed, so mutastic said the tests fail without any mutant, though they pass
  in the build. It now finds the build's classes there and passes. That JVM gets the classes as
  built, though, and coverage can't see it: so a mutant only it could catch was missed, and `main`
  read as reached by no test. The run now says so after its `coverage` line, for each test class
  that starts a process and names a class with a `main`, and says to call what `main` calls from
  a test instead. When that's the only test, the reason the coverage pass recorded nothing says
  so too. The Rust edition has no such gap: a binary a test runs has the mutant in it. See [Which
  tests reach a line](https://docs.mutastic.io/concepts/results/#which-tests-reach-a-line).
