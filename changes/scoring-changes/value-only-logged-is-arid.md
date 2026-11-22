- **A value worked out only for a log line is skipped as arid.** In `log.info("took {}",
  System.currentTimeMillis() - start)` the subtraction's mutants only change what the log says, so
  they're now `arid: only feeds a log line`, part of `mutastic:output`. The value is followed in the
  bytecode, however the source is wrapped: through arithmetic, boxing, `"a" + x`, `String.format`,
  a varargs array, a ternary and the methods of `java.time` and `Math`, and through a local, as in
  `long took = now - start; log.info("took {} ms", took)`, when every read of the local on every
  path, a loop's and a `catch`'s included, is a log call's or a print's argument. A value that
  reaches anything else stays counted: logged and returned, compared, stored in a field, recorded
  as a metric, passed to another method, captured by a lambda. Code that logs timings gets fewer
  mutants, and its score can go up; `arid: [mutastic:output]` on a `mutate:` rule, or `@DoMutate`,
  brings them back. The Rust edition skips a `let` read only by output macros.
