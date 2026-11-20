- **A wrapper in a directory with a space gets commands that run as they're copied.** Since
  mutastic prints the Gradle or Maven it really runs, a wrapper under `/Users/me/My Projects`
  started a command a shell splits at the space. Its path is now quoted, `'/Users/me/My
  Projects/gradlew'`, or for cmd `"C:\My Projects\gradlew.bat"`, and so is the build's directory
  after `-p` or `-f`: in the terminal, the agent's and share-ready text, the events and the
  report's `mutastic.commands`. The HTML report offers these to copy, and a Windows directory after
  the launcher, `-p C:\dir`, which it left out. Its check reads quotes as a shell does: a report
  could get another command past it with a `'` inside double quotes, or `$(…)` in a `<…>`, and
  can't now.
