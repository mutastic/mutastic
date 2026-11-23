- **`stream/swap-window` and `stream/swap-fold-scan`, in `extended`: Java 24's gatherers.**
  `Gatherers.windowFixed` and `windowSliding` swap places, which finds batches that overlap where
  they shouldn't, unseen by a test with no more elements than one window. `Gatherers.fold` and
  `scan` swap places, which finds a running total where the final one was meant, unseen by a test
  with one element. A window of one is the same either way, and is skipped as equivalent. The Rust
  edition has no gatherers. See [Window
  swap](https://docs.mutastic.io/concepts/operators/streams/#swap-window).
