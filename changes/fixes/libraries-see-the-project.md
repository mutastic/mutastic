- **A library that looks a class up by its own loader finds the project's.** The worker kept the
  project's libraries on the JDK's class path, below which it loads the project, so a test whose
  library resolves the project's classes itself failed without any mutant in: spring-core's
  `SerializationUtils.deserialize`, as in spring-petclinic's `VetTests#serialization`, and
  `Class.forName` in a library. The worker's system class loader now defines the libraries, finds
  the project behind them, and gives each class its jar and signers as the build does;
  `java.class.path` lists them too. A signed jar whose class doesn't match its signature is
  refused, as the JDK refuses it.
