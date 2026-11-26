- **The editions' contract takes a third edition without editing a shared entry.** `editions` is
  now a binding block per edition: its selector (was `engineSlots`), error names (was
  `errorPairs`' names), arid reasons and examples, twin shapes, sources, test libraries and the
  capture masks. A difference names only the editions that deviate, each with its kind, and a
  pending one its issue and target release; 37 slots every edition fills (`each`) take their words
  from the bindings. `docs/scripts/contract.mjs` is the shared tooling every edition calls
  (`check`, the release rule `release`, and `pin`, `prune` and `newest`), and release-check fails
  on a pending entry for Java with no issue or target. The capture comparison fails rather than
  warns, its masks built from the bindings; the 15 pairs that still differ are recorded as N17 and
  C1 to C6 (mutastic-rs #196). `docs/scripts/contract.test.mjs` proves a dummy Swift edition joins
  with its binding, a capture, a pin and its own differences. No edition does anything new.
