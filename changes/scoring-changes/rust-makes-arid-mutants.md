- **The Rust edition makes its arid mutants, and skips them with a reason.** An error's or a
  failed check's text (`mutastic:messages`) and every mutant of a `Debug` or `Hash` impl
  (`mutastic:debug-representations`) are made and skipped, so they're counted as arid, a
  `mutate:` rule brings them back, and `MUTASTIC_ARID=audit` runs them. A `Display` impl's whole
  body now counts. The editions' contract gives each rule both editions.
