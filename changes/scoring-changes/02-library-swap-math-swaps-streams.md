- **`library/swap-math` swaps a stream's and a collection's `min` and `max` too.** `Stream.min`
  and `max` with a comparator, `IntStream`'s, `LongStream`'s and `DoubleStream`'s, `Collections.min`
  and `max`, and `Collectors.minBy` and `maxBy` swap places, as `Math.min` and `max` already did,
  and as the Rust edition does for an iterator's. It's in the defaults, so runs get more mutants
  and scores move with them; no existing id changes. See [Math method
  swap](https://docs.mutastic.io/concepts/operators/library/#math-method-swap).
