- **`library/swap-ends`, in `stronger`: one end of a collection for the other.** `getFirst` and
  `getLast`, a `Deque`'s `peekFirst` and `peekLast` and `pollFirst` and `pollLast`, a `SortedSet`'s
  `first` and `last`, and a `SortedMap`'s `firstKey` and `lastKey` and `firstEntry` and `lastEntry`
  swap places. It finds the newest taken where the oldest was meant, which tests with one element
  can't see. The Rust edition has it too, for `first` and `last` and `front` and `back`. See [Swap
  ends](https://docs.mutastic.io/concepts/operators/library/#swap-ends).
