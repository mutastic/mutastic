- **The agent format names the project's text before the fence by a number.** A flaky test's name
  and what it failed with, a likely flaky test, a stale ignore's id, a method with repeated
  ignores and a report's path came before the fence, among mutastic's instructions, so a project
  could name a test with a sentence addressed to the agent and have it read as mutastic's. Each
  such line now says `[1]` where the value was, and the fence opens with `[1] StockTest#sells`.
  The lines say the same as before, and so do `verify` and the gate where they name such a test.
