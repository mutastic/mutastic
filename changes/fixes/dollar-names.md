- **Classes with a `$` of their own, or no SourceFile, are named as the source names them.** A
  class named `$Cache`, or one compiled with no SourceFile attribute (`-g:lines`, or bytecode a
  tool wrote), printed as `com/example/cache/.java` and `.Cache.size()`, and its suggested test
  called `.Cache.size(9)`, which isn't Java. The name is now cut only at a `$` between two other
  characters, a class without a SourceFile is found in its top-level class's file by what its
  class file says of its nesting, and a suggested test is offered only when every class it names
  is written as the source declares it. `mutants` also leaves out what a run skips as generated:
  on realworld-micronaut, Micronaut 4's `@Generated` `$…$Definition` classes were 3551 of the
  4211 mutants it listed.
