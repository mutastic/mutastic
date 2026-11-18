package io.mutastic.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/// The JDK the project's own build runs on, apart from mutastic's.
///
/// mutastic runs on JDK 25 or later, and a project's build often can't: an older Gradle, or a
/// Mockito, that doesn't know JDK 25. So the start script, or the native `mutastic` through it,
/// finds a JDK for mutastic alone, and keeps the `JAVA_HOME` it was given in
/// `MUTASTIC_BUILD_JAVA_HOME`, blank for none. The build, and the tests mutastic runs for it, get
/// that one back, so they run on the JDK they'd run on without mutastic. A build plugin runs
/// mutastic inside the build, which has its own already, so nothing is kept.
@Internal
public final class BuildJava {
  /// Where the start script keeps the `JAVA_HOME` it was given.
  public static final String KEPT = "MUTASTIC_BUILD_JAVA_HOME";

  private static final String JAVA_HOME = "JAVA_HOME";

  private BuildJava() {}

  /// A process's `environment` made the build's own: `JAVA_HOME` as it was given, when the start
  /// script kept it, and the kept copy taken out, so a mutastic the build runs starts afresh.
  public static void restore(Map<String, String> environment) {
    var kept = environment.remove(KEPT);
    if (kept == null) {
      return;
    }
    if (kept.isBlank()) {
      environment.remove(JAVA_HOME);
    } else {
      environment.put(JAVA_HOME, kept);
    }
  }

  /// This process's environment as the build gets it.
  public static Map<String, String> environment() {
    var environment = new java.util.HashMap<>(System.getenv());
    restore(environment);
    return environment;
  }

  /// The feature version of the JDK a build started with `environment` runs on, 25 for 25.0.1: its
  /// `JAVA_HOME`'s, else the `java` on its `PATH`'s. Empty when there's none, or it can't be told.
  public static OptionalInt feature(Map<String, String> environment) {
    var home = environment.getOrDefault(JAVA_HOME, "");
    if (!home.isBlank()) {
      var release = Path.of(home, "release");
      try {
        if (Files.isRegularFile(release)) {
          return fromRelease(Files.readString(release, StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        return OptionalInt.empty();
      }
      return settings(Path.of(home, "bin", "java").toString());
    }
    var path = Objects.requireNonNullElse(environment.get("PATH"), "");
    return Wrappers.program("java", path).map(BuildJava::settings).orElse(OptionalInt.empty());
  }

  /// `JAVA_VERSION="25.0.1"` in a JDK's release file, or `"1.8.0_392"` for JDK 8.
  private static final Pattern RELEASE =
      Pattern.compile("(?m)^JAVA_VERSION=\"(?:1\\.)?(?<feature>\\d+)");

  /// `java.specification.version = 25`, as `java -XshowSettings:properties` prints it.
  private static final Pattern SETTINGS =
      Pattern.compile("(?m)^\\s*java\\.specification\\.version = (?:1\\.)?(?<feature>\\d+)");

  static OptionalInt fromRelease(String release) {
    return found(RELEASE, release);
  }

  static OptionalInt fromSettings(String printed) {
    return found(SETTINGS, printed);
  }

  private static OptionalInt found(Pattern pattern, String text) {
    var matcher = pattern.matcher(text);
    return matcher.find()
        ? OptionalInt.of(Integer.parseInt(matcher.group("feature")))
        : OptionalInt.empty();
  }

  /// What `java` says of itself.
  private static OptionalInt settings(String java) {
    try {
      var process =
          new ProcessBuilder(java, "-XshowSettings:properties", "-version")
              .redirectErrorStream(true)
              .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nothing())))
              .start();
      var printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor();
      return fromSettings(printed);
    } catch (IOException e) {
      return OptionalInt.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OptionalInt.empty();
    }
  }

  private static String nothing() {
    return Wrappers.WINDOWS ? "NUL" : "/dev/null";
  }
}
