- **Classes compiled without line numbers are refused, by name, not scored 0%.** Coverage is
  recorded by line, so a class compiled with `-g:none` (or Maven's `<debug>false</debug>`) had
  every mutant on line 1 and reached by no test, though its tests ran it. `run` and `mutants` now
  stop with exit 2, naming the classes and how to compile them with line numbers, as javac does by
  default. Counting the tests that enter a method instead would say a test reaches a mutant on a
  branch it never takes.
