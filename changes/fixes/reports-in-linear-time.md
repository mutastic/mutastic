- **Writing the reports takes as long as the tests are many, not their square, and Duration counts
  it.** Each test that covers a mutant is placed in its file for `mutation.json` and the HTML
  report, and its file was read again for each test: with 2,000 tests in a class, the reports took
  3.1s of a 7.5s run, while the Duration row said 4.1s. Each file is now read once, writing them
  took 93ms, and the Duration row has a `reports` phase, in its total.
