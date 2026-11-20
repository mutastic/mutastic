- **A plugin's filter can skip one mutant, not only a whole method.** `Filter` gains
  `skipMutant(site, mutant)`, asked about each mutant still to run once mutastic's own checks and
  every filter's `skip` have had their say, with the instruction it changes and the code around
  it. So a rule like "this call only records an audit event" can ship as a plugin. It does nothing
  by default, so existing filters are unchanged. A skip with a blank reason stops the run, naming
  the filter; an `ARID` one is brought back by a `mutate:` rule or `@DoMutate`, as mutastic's own
  are. See [Filters](https://docs.mutastic.io/guides/plugins/#filters).
