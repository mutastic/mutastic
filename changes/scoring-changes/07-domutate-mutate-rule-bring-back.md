- **`@DoMutate` and a `mutate:` rule bring back every arid skip in their code.** They brought
  back only a whole method a filter skips, a trivial accessor or a `toString()`, and still
  skipped a log call, a `Thread.sleep`, a capacity, an exception's message and the other arid
  sites inside it. Now each of those runs too, so code under `@DoMutate` or `mutate:` gets more
  mutants, and its score can fall. What's generated, equivalent or unviable, and a `@DoNotMutate`
  or `skip:` rule's skip, still stand; no id changes. The Rust edition's `mutate:` already worked
  this way.
