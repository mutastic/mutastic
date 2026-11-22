- **Code only `main` runs says so, and what to do.** A `main` no test calls read as not covered,
  like any other method. Its line in the Not covered list now says it's reached only from `main()`,
  and to move what it does into `run(args, out)`, which returns the exit code, test `run`, and leave
  `main()` a one-line call to it; a `main` whose mutants are all on one line, as one that only calls
  `run`, gets no such advice. The Rust edition says the same. See [Code reached only from
  main](https://docs.mutastic.io/concepts/results/#code-reached-only-from-main).
