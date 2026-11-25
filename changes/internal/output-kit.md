- **The output kit has a package of its own.** Colour roles, glyphs and labels, escaping and the
  agent's fence, JSON and the events' envelope moved to `io.mutastic.output`, which
  `ArchitectureTest` holds to the JDK alone, so proptastic can share them without mutastic. What
  mutastic prints is unchanged.
