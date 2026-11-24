- **A parameterized test that takes a class of the tests runs against each mutant.** A test in a
  package the code also uses runs as a fresh copy, and so does a test class of that package that
  it takes, as a `@MethodSource` test takes a record of its cases. mutastic selected the test by
  the type the build gave it, which the copy doesn't take: no test ran, every mutant it reached
  counted as caught, and running it on its own after the mutants called it flaky, so its catches
  were dropped and the run failed. It's now selected by the copy's type. A test mutastic can't
  select again is said to be mutastic's own error, naming the test, and never a test that fails on
  its own.
