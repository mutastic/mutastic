- **The Maven module is held to the same boundary as the Gradle one.** `ArchitectureTest`'s rule
  that a build tool knows only the api named `io.mutastic.gradle` alone, so `mutastic-maven` could
  have reached into the engine, the report or the CLI unseen. It names `io.mutastic.maven` too.
