- **The contract says how mutastic seeds and reads proptastic.** Decision 31: found by what the
  tests link, never a name (Java its entry point's class on the test classpath, Rust the
  `proptastic` package in Cargo.lock, through wrapper crates too); seeded with the run's seed as
  `0x` and hex digits; saved failures, events, corpus and colour off in every test process; no
  shrinking on a mutant's run; and the counterexample read only from proptastic's report, its
  stacked form joined onto one line. mutastic relies on proptastic keying a property's inputs on
  the test's signature, never its class, as its renamed copies of test classes need. N90 to N93
  record how the editions differ, and the events schema names `proptastic` among the frameworks.
