- **A print to a stream the code is given is counted.** `out.println(...)` on a `PrintStream`
  passed in or held in a field, as `run(args, out)` takes it, was skipped as `arid: only writes
  output`, though a test can hand the code a stream and read it: mutants such a test catches, and
  the ones it misses, went unseen. Only the console is output now, `System.out` and `System.err`
  read straight from there or from a local that holds nothing else. Code that prints to a stream
  of its own gets more mutants, and its score moves; new ids appear, none moves.
