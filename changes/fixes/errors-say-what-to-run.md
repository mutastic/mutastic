- **An error with an option before the command, or a directory that isn't there, says what to
  run.** `mutastic --format=agent run` says mutastic takes its command first and gives the line
  with it first, `mutastic run --format=agent`, where it said `--format=agent` wasn't a command.
  `mutastic run pricng` says where it looked, and that there's a directory called `pricing`, with
  the command on it. Both read as the Rust edition's.
