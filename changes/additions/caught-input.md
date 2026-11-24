- **A card names the input a property caught a mutant with.** When a QuickTheories, Kotest or
  junit-quickcheck property catches a mutant, `verify`, `explain` and the card say which input it
  failed on, ``caught by ListsTest#reversesTwice with `[0]` ``, and `mutant-finished` has it as
  `caughtInput`, as `mutation.json` has `mutastic.caughtInput`. In a run of every mutant it's the
  first input that failed, unshrunk; a run of mutants named by id runs the catching property once
  more with shrinking on, for the smallest. `run-started` says which property-testing frameworks the
  tests use in `propertyTests`, with the seed their inputs were drawn from and the frameworks
  mutastic couldn't seed.
