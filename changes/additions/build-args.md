- **Arguments for the build tool.** What follows `--` goes to Gradle or Maven on each build call,
  and never to the tests: `mutastic run :app -- -Dorg.gradle.java.installations.paths=/jdk-21`. Each
  command a run offers keeps it. `MUTASTIC_BUILD_ARGS`, or `build:`'s `args:` in `mutastic.yaml`,
  gives the same to every run, and no command repeats those, so a secret goes there. An argument
  that fights how mutastic drives the build is refused, with the reason. See
  [Arguments for the build](https://docs.mutastic.io/guides/configuration/#arguments-for-the-build).
