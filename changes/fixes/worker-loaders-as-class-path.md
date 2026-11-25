- **A test sees its package's annotations, and its stack, as it does in the build.** A test that
  read a package's annotations (jsoup checks for JSpecify's `@NullMarked`) found none, and one that
  reported its caller from a stack frame found `mutastic-production//` in front of the class's
  name, so both failed without any mutant. The worker's class loaders now give a package's
  `package-info` to the JDK when it asks, and have no names, as the build's class path loader has
  none a frame shows.
