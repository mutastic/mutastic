- **The agent format escapes the last values it showed as they were.** A method's name in a
  repeated-ignores note, the last run's operators in `vs last run`, and the catching test's name
  and a skip's reason in a mutant's fate with `--only` went out unescaped, free to carry an ANSI
  escape or a bidi control. Each is now shown escaped, `\x1b`, as every other value of the
  project's is.
