- **An error ends in the one command to run, in the Rust edition's words.** A mistake in the
  command line no longer ends in a `usage:` line. With no close name, it ends in the command that
  lists what there is, `mutastic run --help`, or in yours with a `<…>` to fill in: a missing value
  (`--format=<format>`, with the values it takes), a missing id (`<id>`, where the example was a
  real id that could be run as it was) or reason (`--reason="<why>"`, the directory and options
  kept). An id no mutant has ends in `mutastic mutants`; a directory with no build says where it
  looked and ends in your command on `<project directory>`. `--workers=0` says what it got, and
  an unknown id, a missing reason and a directory with no project read as they do in the Rust
  edition, so a script or an agent reads one message in both.
