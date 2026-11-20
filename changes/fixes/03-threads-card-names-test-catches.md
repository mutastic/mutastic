- **A thread's card names the test that catches it.** A missed `start()`, `execute()` or
  `submit()` said only that no test checked the effect of calling it. Its card now says no test
  waits for the work and checks what it did, and to join the thread or wait on a latch first. A
  removed `interrupt()` asks for a test that has a waiting thread stop; one that sets the flag
  again after an `InterruptedException` asks for the flag to be checked. A removed `unlock()` asks
  for another thread to take the lock, `setDaemon(true)` for `isDaemon()`, and a handler for
  exceptions for a task that throws, run with `start()` or `execute()`, as one from `submit()`
  never reaches it. A pool's `awaitTermination` that's always true asks for a task still running
  when the wait ends. Each card's test was run against its mutant, in every shape javac compiles
  the call to. The Rust edition's card for a removed `thread::spawn` says the same.
