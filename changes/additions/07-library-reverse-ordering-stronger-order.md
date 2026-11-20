- **`library/reverse-ordering`, in `stronger`: an order turned round.** `a.compareTo(b)` becomes
  `b.compareTo(a)`, a comparator's or a list's `reversed()` is left out, `Collections.reverseOrder(c)`
  becomes `c`, and `Comparator.reverseOrder()` becomes `naturalOrder()`. It finds a sort the wrong
  way round that tests with input already in order can't see. A `compareTo` only tested for `0` is
  skipped as equivalent. The Rust edition has had it, for `cmp`. See [Reverse
  ordering](https://docs.mutastic.io/concepts/operators/library/#reverse-ordering).
