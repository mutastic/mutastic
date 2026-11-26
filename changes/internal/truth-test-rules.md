- **The truth tests keep to the helpers that keep their work.** `TruthTestRulesTest` holds every
  `*TruthTest` to compiling through `Javac`, never `javax.tools` itself, to no `@TempDir`, and to
  defining no class of its own: no loader, no `Lookup.defineClass`. Four tests' own loaders
  (`CardTruthTest`, `DiscardTruthTest`, `ReturnTruthTest`, `StoreTruthTest`) are named as they
  are today.
