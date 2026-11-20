- **`library/plain-division`, in `stronger`: division that rounds towards zero.** `Math.floorDiv`
  and `Math.floorMod` become `/` and `%`, so a remainder that's wrong for negative numbers, `-1 %
  3` being `-1` where `floorMod` gives `2`, shows unless a test has one. A JDK length or count
  divided by a constant that isn't negative is skipped as equivalent. The Rust edition has it too,
  for `div_euclid` and `rem_euclid`. See [Plain
  division](https://docs.mutastic.io/concepts/operators/arithmetic/#plain-division).
