package io.mutastic.gradle;

import io.mutastic.api.BuildErrors;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// Which JDKs a Gradle runs on, from Gradle's compatibility matrix
/// (https://docs.gradle.org/current/userguide/compatibility.html): the first Gradle that runs on
/// each JDK. A Gradle older than its JDK's first fails before it reads the build, in words that
/// don't say why: Gradle 8.10 on JDK 25 says only `25.0.1`.
final class GradleJdks {
  private GradleJdks() {}

  /// Each JDK, and the first Gradle that runs on it.
  private static final NavigableMap<Integer, Version> FIRST =
      new TreeMap<>(
          Map.ofEntries(
              Map.entry(11, Version.of("5.0")),
              Map.entry(12, Version.of("5.4")),
              Map.entry(13, Version.of("6.0")),
              Map.entry(14, Version.of("6.3")),
              Map.entry(15, Version.of("6.7")),
              Map.entry(16, Version.of("7.0")),
              Map.entry(17, Version.of("7.3")),
              Map.entry(18, Version.of("7.5")),
              Map.entry(19, Version.of("7.6")),
              Map.entry(20, Version.of("8.3")),
              Map.entry(21, Version.of("8.5")),
              Map.entry(22, Version.of("8.8")),
              Map.entry(23, Version.of("8.10")),
              Map.entry(24, Version.of("8.14")),
              Map.entry(25, Version.of("9.1.0")),
              Map.entry(26, Version.of("9.4.0")),
              Map.entry(27, Version.of("9.8.0"))));

  /// A Gradle that can't run on the build's JDK: the Gradle, the JDK, the newest JDK the Gradle
  /// runs on, and the first Gradle that runs on the JDK, when the matrix knows it.
  record TooOld(Version gradle, int jdk, int newest, Optional<Version> first) {}

  /// Why `gradle` can't run on `jdk`, if it can't. A JDK newer than the matrix knows is one a
  /// Gradle can't run on when it can't run on the newest the matrix knows.
  static Optional<TooOld> tooOld(Version gradle, int jdk) {
    var known = FIRST.floorEntry(jdk);
    if (known == null || gradle.compareTo(known.getValue()) >= 0) {
      return Optional.empty();
    }
    var newest =
        FIRST.entrySet().stream()
            .filter(entry -> gradle.compareTo(entry.getValue()) >= 0)
            .map(Map.Entry::getKey)
            .reduce((first, second) -> second)
            .orElse(FIRST.firstKey() - 1);
    return Optional.of(new TooOld(gradle, jdk, newest, Optional.ofNullable(FIRST.get(jdk))));
  }

  /// The Gradle a wrapper runs: its properties' `distributionUrl`, `.../gradle-8.10.2-bin.zip`.
  static Optional<Version> wrapped(Path wrapper) {
    var properties = wrapper.resolveSibling("gradle/wrapper/gradle-wrapper.properties");
    try {
      if (!Files.isRegularFile(properties)) {
        return Optional.empty();
      }
      var read = new Properties();
      read.load(new StringReader(Files.readString(properties)));
      var url = DISTRIBUTION.matcher(read.getProperty("distributionUrl", ""));
      return url.find() ? Optional.of(Version.of(url.group("version"))) : Optional.empty();
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /// `gradle-8.10.2-bin.zip`, `gradle-9.1.0-rc-1-all.zip`: the version's numbers.
  private static final Pattern DISTRIBUTION =
      Pattern.compile("gradle-(?<version>\\d+(?:\\.\\d+)*)(?:-[\\w.-]*)?-(?:bin|all)\\.zip");

  /// The command that upgrades the wrapper to the first Gradle that runs on the JDK, from
  /// `directory`, as the person would type it with `wrapper`.
  static String upgrade(TooOld tooOld, String wrapper, Path directory) {
    var version = tooOld.first().map(Version::toString).orElse("<version>");
    return BuildErrors.command(
        wrapper, directory, "-p", List.of("wrapper", "--gradle-version", version));
  }

  /// What mutastic says: the build's JDK and its Gradle, the two ways out, and `build`'s command,
  /// which upgrades the wrapper.
  static String said(TooOld tooOld, BuildErrors.Build build) {
    var upgrade =
        tooOld
            .first()
            .map(first -> "Gradle %s or later".formatted(first))
            .orElse("a Gradle that runs on JDK " + tooOld.jdk());
    return ("%s's build runs on JDK %d, and its Gradle, %s, runs on JDK %d at most.\n"
            + "Set JAVA_HOME to a JDK %d or older, and mutastic still finds its own JDK 25. Or"
            + " upgrade the wrapper to %s. %s's output is in %s. To upgrade, with JAVA_HOME at the"
            + " older JDK:\n  %s")
        .formatted(
            build.project(),
            tooOld.jdk(),
            tooOld.gradle(),
            tooOld.newest(),
            tooOld.newest(),
            upgrade,
            build.tool(),
            shown(build.log()),
            build.command());
  }

  /// `log` from where the person is, when it's inside it.
  private static Path shown(Path log) {
    var here = Path.of("").toAbsolutePath().normalize();
    var absolute = log.toAbsolutePath().normalize();
    return absolute.startsWith(here) ? here.relativize(absolute) : absolute;
  }

  /// A Gradle version's numbers, `8.10.2`, compared number by number: `8.10` is `8.10.0`.
  record Version(List<Integer> numbers) implements Comparable<Version> {
    Version {
      numbers = List.copyOf(numbers);
    }

    static Version of(String version) {
      return new Version(
          java.util.Arrays.stream(version.split("\\.")).map(Integer::valueOf).toList());
    }

    @Override
    public int compareTo(Version other) {
      for (int at = 0; at < Math.max(numbers.size(), other.numbers.size()); at++) {
        int compared = Integer.compare(number(at), other.number(at));
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    }

    private int number(int at) {
      return at < numbers.size() ? numbers.get(at) : 0;
    }

    @Override
    public String toString() {
      return String.join(".", numbers.stream().map(String::valueOf).toList());
    }
  }
}
