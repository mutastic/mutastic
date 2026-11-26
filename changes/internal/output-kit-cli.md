- **The CLI's share of the output kit joined it.** The progress block, its bar and heartbeat,
  help's layout, the grading of an error's commands and the corrected command line moved from
  `mutastic-cli` to `io.mutastic.output`, which knows only the JDK, so proptastic can share them.
  What mutastic prints is unchanged.
