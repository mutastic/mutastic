- **A Gradle 8 build that declares no JUnit Platform launcher runs.** Gradle 8 runs the platform
  with a launcher of its own, so many builds never declare `junit-platform-launcher`, and
  mutastic's test worker then didn't start: "internal error", exit 4. mutastic now adds the build's
  own launcher, from its repositories, at the version of its `junit-platform-engine`, as Maven
  runs already did. Where that can't be resolved, mutastic says to declare it, rather than add one
  of another version, which the platform doesn't run on.
