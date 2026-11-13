## Working test-first

Work test-first with mutastic, one behaviour of the spec at a time:

1. Write one test from the spec and run the tests: it must fail. Write no production code
   before it, but a stub so it compiles.
2. Write the least production code that passes it.
3. Run `mutastic run --since=HEAD --format=agent` on the cycle's lines.
4. For each card, check its value against the spec, write its test, and prove it with the card's
   `mutastic verify`. If the spec and a test disagree, stop and ask me.
5. Refactor if the code needs it, with the tests green, and run mutastic again.
6. Commit. The commit is where the next cycle starts.
