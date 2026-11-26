package io.mutastic.maven;

import io.mutastic.api.BuildErrors;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// The JDK a Maven build's toolchain asks for, when `~/.m2/toolchains.xml` has none that matches:
/// the toolchains plugin says so, then names each toolchain it looked for on a line of its own,
/// `[ERROR] jdk [ vendor='temurin' version='21' ]`.
///
/// The compiler plugin's `jdkToolchain` isn't one of these: with no toolchain that matches it, the
/// compiler plugin (3.10 to 3.16) compiles with the JDK Maven runs on, and says nothing.
final class MavenToolchain {
  private MavenToolchain() {}

  /// What the toolchains plugin says before the toolchains it looked for.
  static final String NOT_FOUND = "Cannot find matching toolchain definitions";

  /// `[ERROR] jdk [ vendor='temurin' version='[17,)' ]`.
  private static final Pattern JDK =
      Pattern.compile("^(?:\\[ERROR\\] )?jdk \\[ (?<requirements>.*) \\]$");

  /// `version='21'`, `vendor='temurin'`.
  private static final Pattern REQUIREMENT = Pattern.compile("\\b(?<key>\\w+)='(?<value>[^']*)'");

  /// The JDK Maven found no toolchain for, from what it said: empty when that isn't why it failed.
  static Optional<BuildErrors.Toolchain> missing(List<String> output) {
    if (output.stream().noneMatch(line -> line.contains(NOT_FOUND))) {
      return Optional.empty();
    }
    return output.stream()
        .map(line -> JDK.matcher(line.strip()))
        .filter(java.util.regex.Matcher::matches)
        .findFirst()
        .flatMap(jdk -> toolchain(jdk.group("requirements")));
  }

  private static Optional<BuildErrors.Toolchain> toolchain(String requirements) {
    Optional<String> version = Optional.empty();
    Optional<String> vendor = Optional.empty();
    var each = REQUIREMENT.matcher(requirements);
    while (each.find()) {
      switch (each.group("key")) {
        case "version" -> version = Optional.of(each.group("value"));
        case "vendor" -> vendor = Optional.of(each.group("value"));
        default -> {
          // an id or another requirement: the version and the vendor say which JDK
        }
      }
    }
    var named = vendor;
    return version.map(asked -> new BuildErrors.Toolchain(asked, named));
  }

  /// Where Maven looks for toolchains, said after "found none".
  static final String WHERE = " in ~/.m2/toolchains.xml";

  /// The argument that tells Maven which toolchains file has the JDK, to fill in.
  static final String ARGUMENT = "--toolchains <path/to/toolchains.xml>";
}
