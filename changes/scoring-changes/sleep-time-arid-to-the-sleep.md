- **Only a sleep's time is arid, followed all the way to the sleep.** A number, or arithmetic,
  that goes only into how long `Thread.sleep` or `TimeUnit.sleep` waits is skipped as `arid: only
  sets how long to wait` however it gets there: the `1000` in `seconds * 1000L` too, and through
  `Duration.ofSeconds` or `TimeUnit.toMillis`, as `Thread.sleep(Duration.ofSeconds(n * 2))`. A
  `join`'s time limit now counts, as a timeout given to `Future.get` always did: a shorter limit
  gives up on the work sooner, and a test can see that. So does a number made negative for
  `Thread.sleep(long)`, which throws for it. The Rust edition makes and skips the same:
  `thread::sleep` and an awaited `sleep` removed (`mutastic:waits`), and a number that goes into a
  sleep (`mutastic:wait-times`), where it made no mutant before. See [Wait
  times](https://docs.mutastic.io/reference/arid/#wait-times).
