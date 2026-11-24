- **A multi-release build's Java 11 classes load in the tests, and their mutants run.** A Maven build
  that compiles classes into `META-INF/versions/11` and puts that directory on the tests' classpath
  with Surefire's `additionalClasspathElements`, as jsoup's does, had every test that reached them
  fail without any mutant, as mutastic never read that setting. They're now loaded with the
  module's own classes, in the order Surefire gives, and their sources come from the directory the
  build compiles them from (`src/main/java11`). Another module's multi-release jar gives the classes
  the JDK the tests run on would load from it, as the JVM's class path does.
