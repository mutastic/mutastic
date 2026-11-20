- **A log call's format string is arid with worked-out values after it (stronger).**
  `log.info("took {} ms", System.currentTimeMillis() - start)` kept a `mutastic/string-constant`
  mutant on `"took {} ms"`: the walk from the string to the logger stopped at the subtraction, a
  call, boxing, a varargs array, a `+` or a `? :` on the way. It now follows the string through
  them to the call that takes it, so the string is skipped as arid, as one with nothing after it
  was; one that may reach anything else on any path still counts. `stronger` runs have fewer
  mutants, and scores move a little. `mutastic/regex-constant` follows an expression the same
  way, so one with a worked-out replacement after it, `a.replaceAll("a+", b + "!")`, is now
  changed. No id changes.
