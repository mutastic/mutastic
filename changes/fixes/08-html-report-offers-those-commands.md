- **The HTML report offers those commands to copy.** A mutant's commands that start with the
  wrapper by its path (`sh ./gradlew`, `../gradlew`, `/repo/mvnw`), Gradle or Maven by an absolute
  path, or `.\gradlew.bat` on Windows were left out of the report's page, as it took only
  `./gradlew`, `gradle` and `mvn`. It still offers nothing else: a path is plain names between its
  separators, ending in the wrapper's or the tool's name.
