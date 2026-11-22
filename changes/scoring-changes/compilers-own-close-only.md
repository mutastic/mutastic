- **A close you write yourself runs, even in a method with a try-with-resources.** The skip for
  the close javac writes for a try-with-resources worked by method: in any method that called
  `addSuppressed`, every `close()` behind a null check, or closed in two places, was skipped as
  `generated: closes a try-with-resources resource`. So an `if (out != null) out.close()` beside a
  try-with-resources, or a close written out by hand with its own `addSuppressed`, was hidden,
  though a test could catch it. Now only the exact code each compiler writes is skipped, found
  from the resource's own handler: javac 11 and later's, javac 7 to 10's (with javac 9 and 10's
  `$closeResource`, which wasn't skipped before) and ECJ's, whose own null and identity checks
  weren't either. Methods like that get more mutants, and their scores can fall; no id changes.
  Kotlin's `use` is skipped the same way, as ``generated: closes a `use` resource``: its
  `closeFinally` call ran before.
