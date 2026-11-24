- **What each test framework needs is decided in one place.** The JUnit Platform, TestNG, JUnit
  4's vintage engine and Quarkus are each a part in `io.mutastic.engine.tests`, behind
  `TestFrameworks`, in place of one class of checks the engine called from three places.
  `ArchitectureTest` holds the engine's parts apart from its core and from each other.
