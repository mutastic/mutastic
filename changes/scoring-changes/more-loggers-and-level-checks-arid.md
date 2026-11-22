- **More loggers' calls are output, and so is a log level's check around nothing else.** The
  `mutastic:output` rule now knows SLF4J's fluent builder (`log.atInfo().log(...)`), Logback's
  `Logger`, Log4j 2's builder, Log4j 1, JBoss Logging, kotlin-logging, Flogger and tinylog, beside
  SLF4J, Log4j 2, `java.util.logging`, `System.Logger` and Commons Logging. And in `if
  (log.isDebugEnabled()) { log.debug(...); }` the check's mutants are skipped as `arid: only
  decides whether output is written`, when every statement it guards writes output and does
  nothing else; a check that also stores a value, calls a method of the code's own, has an `else`
  or returns stays counted. Code that logs through these gets fewer mutants, and its score can go
  up; `arid: [mutastic:output]` on a `mutate:` rule, or `@DoMutate`, brings them back.
