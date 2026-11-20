- **Help fits 100 columns.** The exit codes, `--help`'s list of commands and the long `ignore`
  example ran to 150 columns and more, so a terminal broke them mid-word. They now wrap under
  their first line, and the example breaks before an option with ` \`, as the Rust edition's
  help does. `--progress` also says that the agent and jsonl formats always get a line at least
  every 30s.
