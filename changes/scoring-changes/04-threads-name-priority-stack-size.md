- **A thread's name, its priority, its stack size and how long a pool's idle threads live are
  skipped as arid.** None of them changes whether, when or how often work runs. A name given to
  `new Thread`, `setName`, `Thread.Builder`'s `name` (and its start index) or Guava's
  `setNameFormat` is `arid: only names a thread`; a priority from 1 to 10, or `setPriority`
  removed, is `arid: only sets a priority hint`, as Linux ignores priorities by default and a
  virtual thread always does; a `ThreadPoolExecutor`'s keep-alive is `arid: only sets how long
  idle threads live`; and a stack size is `arid: only sizes a thread's stack`. A class that reads
  names (`getName()`, other than into a log line) or is a `ThreadFactory` keeps its name mutants,
  and one that reads priorities keeps those. A value that throws, and a keep-alive or a stack size
  of zero, still run, and so do `start`, `interrupt`, `join`, `setDaemon` and pool and queue
  sizes. Runs on code that sets threads up get fewer mutants, and scores can go up; `@DoMutate`
  and a `mutate:` rule bring them back. The Rust edition skips names, stack sizes and tokio's
  keep-alive too.
