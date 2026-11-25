- **`--exclude-tests` takes a test as a run names it, and says when a name matches none.** A run
  names a test with its parameter types, `com.example.PricingTest#bigOrders(com.example.Cart,
  int)`, and that name, given to `--exclude-tests` or `exclude-tests:`, was taken, left nothing out
  and said nothing. Now every form a run prints is taken: a test class by its name or full name,
  `Class#method` with every overload, and one overload by its parameter types, full or by name
  alone; a comma between them no longer splits the option. One rule matches them everywhere a run
  leaves tests out: the coverage pass, each mutant's tests and the flaky watch alike. A name that
  matches no test leaves nothing out, and the run says so, `! left out nothing: PricingTest#bigOrder
  names no test of pricing. There's one called PricingTest#bigOrders.`, in every format, with
  `matched: false` and the close name as `maybe` in `run-finished`. See
  [Leaving a test out](https://docs.mutastic.io/guides/configuration/#leaving-a-test-out).
