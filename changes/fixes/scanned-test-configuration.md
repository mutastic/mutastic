- **A Spring test configuration in the app's own package no longer fails every test.** A test
  `@Configuration` or `@SpringBootConfiguration` in a package production code also uses, whose
  component scan finds it, gave Spring two configurations: the one the tests named, as mutastic
  ran it, and the class the scan found. Each declared the same beans, so Spring refused them and
  every test failed with `Failed to load ApplicationContext` before any mutant ran. A test class
  that Spring's scan can find is now the class the tests name.
