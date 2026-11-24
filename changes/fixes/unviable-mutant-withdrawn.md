- **A mutant whose class the JVM would reject is skipped as unviable, and the run goes on.** One
  such mutant stopped the whole run with an internal error, as mutastic's own operators were
  trusted to make classes that load. It's now withdrawn with the verifier's reason, `unviable: Bad
  type on operand stack in …`, out of the score, as a plugin's that wouldn't load already was. The
  one found, `mutastic/logical-connector`, took Kotlin's `style?.align ?: Start` for an `&&` or
  `||`: its two tests each keep the value they test, so swapping them can't load, and it's no longer
  taken for one. See [Skipped mutants](https://docs.mutastic.io/concepts/results/#skipped-mutants).
