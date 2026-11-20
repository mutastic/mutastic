- **A file one mutant's tests leave in the temporary directory can't change the next mutant's
  result.** Each worker had a temporary directory of its own, but one worker runs many mutants,
  so a mutant that removes a `Files.delete` left its file there, and a test of another mutant that
  removes the write could read that file and pass: missed in one run, caught in the next. Now what
  each run's tests add to the worker's temporary directory is deleted once they've answered, so
  the next mutant's tests find it as the last ones did. What was there before stays, as a server
  the coverage pass started can hold on to a directory there. The Rust edition gives each test
  process an empty temporary directory of its own. See [How a run
  works](https://docs.mutastic.io/concepts/how-a-run-works/#each-mutant-against-the-tests-that-reach-it).
