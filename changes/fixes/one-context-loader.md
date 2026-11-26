- **Spring 5's proxies are made once in a run, as in the build.** Each mutant's run had a thread
  context class loader of its own, and Spring 5 keeps the CGLIB proxy it made of a class by that
  loader, so a later run made it again and the JVM refused it as defined twice: tests that pass in
  the build failed there, and judged nothing. The worker now has one context class loader for its
  life, which finds every class and resource through the current run's own, so Spring finds the
  proxy it made, and a class asked for by name is always the current run's. On a Spring Boot 2.6
  project with MyBatis, no class is defined twice and 267 mutants are caught, where 248 were once
  those failures stopped counting.
