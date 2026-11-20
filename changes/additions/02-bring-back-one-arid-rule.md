- **Bring back one arid rule: `arid:` on a `mutate:` rule.** `arid: [mutastic:waits]` under a
  `mutate:` rule in `mutastic.yaml` brings back only that rule's skips in the code it covers, and
  `@DoMutate(arid = "mutastic:waits")` does the same in the code (`mutastic-annotations` gains
  `arid()`). Without it a `mutate:` rule brings back every arid skip, as before. An id mutastic
  doesn't have is refused, with the one meant when it's close: `mutastic:wait isn't an arid rule.
  There's one called mutastic:waits.` The `mutastic.yaml` schema has the key and its ids. The
  Rust edition shares `mutastic:thread-names`, `mutastic:stack-sizes` and
  `mutastic:keep-alive`.
