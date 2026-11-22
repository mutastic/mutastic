- **More capacities are arid, and a negative one counts.** The number in the arithmetic, the `2`
  in `new ArrayList<>(n * 2)`, the JDK's `HashMap.newHashMap` and its kin, Guava's sized factories
  (`Lists.newArrayListWithExpectedSize` and the like) and a hash table's load factor while it stays
  above zero (`arid: only sets when a hash table grows`) are skipped as arid. A number changed to a
  negative one there, as `new ArrayList<>(16)` to `-1`, now counts: it always throws. The Rust
  edition makes and skips a number or arithmetic in `with_capacity`, `reserve` and
  `reserve_exact`. See [Capacity](https://docs.mutastic.io/reference/arid/#capacity).
