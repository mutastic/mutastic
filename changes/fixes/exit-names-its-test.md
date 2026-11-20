- **A test whose code exits says so.** A caught mutant's card says `caught by
  CliTest#printsTheDiscount, by the JVM exiting with status 2`, and its event has `caughtWith:
  "exit: 2"` and `failureOrigin: "exit"`. The Rust edition says the same for `process::exit`.
