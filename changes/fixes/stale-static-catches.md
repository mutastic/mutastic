- **A test that keeps state in a static no longer catches mutants it can't.** A test class the
  worker shares between runs, such as one the project names for a JDK service, kept its statics
  from one mutant's run to the next, so a test that counts its runs, or remembers what the code
  said, failed against mutants it never fails against in the build, and they counted as caught.
  A catch by a test whose run can see state a test-source class keeps in its statics now runs
  again in a fresh loader, where every static starts clean, and counts only if it fails there too;
  with `--kill-matrix`, so does each catcher after the first.
