- **The build's everyday commands are one word each, with [just](https://just.systems).** `just`
  lists them; `just check` is everything a pull request needs to pass, as `./gradlew build` is, and
  `just ci` is what CI runs. The words mean the same in mutastic-rs, and a move off Gradle changes
  the recipes beneath them, not the words people and agents type.
