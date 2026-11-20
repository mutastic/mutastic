- **A skip says which arid rule made it, and how to undo it.** `explain` on a mutant an arid rule
  skipped has an `arid` row: the rule's id, what it skips, a link to its docs and the
  `mutastic.yaml` rule to copy that runs it. The HTML report's "Why it was skipped" says the same,
  with a copy button. Events carry the id as `skipRule` (`mutant-finished`) and the row as `arid`
  (`mutant-explained`), mutation.json as `mutastic.skipRule`, and an arid audit gives the rule to
  copy for each method where a test's assertion caught an arid mutant.
