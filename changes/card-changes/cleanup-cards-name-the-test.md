- **A removed cleanup call's card says what to look at.** A `close()`, `closeQuietly()`, a pool's
  `shutdown()` or `shutdownNow()`, `addShutdownHook` and a `Cleaner`'s `clean()` had the card any
  call gets, "no test checks the effect of calling `close()`". Each now names its test: a `close()`
  in a `finally` wants a resource you can inspect, closed when the method returns and when it
  throws; one on a single path, such as a `catch`, wants that path taken first; a JDBC
  `Connection`'s wants a `DataSource` you pass in and `close()` verified, a statement's or result
  set's a mock; a pool's wants the pool passed in and `isShutdown()`; a hook's wants
  `removeShutdownHook` to return true; and `clean()` wants its effect checked right away. Each
  named test is run against its mutant. See [Remove void
  call](https://docs.mutastic.io/concepts/operators/calls/#remove-void-call).
