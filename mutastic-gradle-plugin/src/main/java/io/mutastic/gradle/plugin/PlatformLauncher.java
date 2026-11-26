package io.mutastic.gradle.plugin;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ResolveException;

/// The JUnit Platform launcher mutastic's worker needs, when the test task's classpath has the
/// platform and no launcher: Gradle 8 runs the platform with a launcher of its own, so many builds
/// never declare one. It's the build's own, from its repositories, at its engine's version, as the
/// platform runs only on one version of each; never mutastic's, which would be another. None when
/// it can't be resolved: the engine then says what to add.
final class PlatformLauncher {
  /// `junit-platform-engine-1.10.2.jar`, and its version.
  private static final Pattern ENGINE =
      Pattern.compile("junit-platform-engine-(?<version>\\d[^/\\\\]*)\\.jar");

  private PlatformLauncher() {}

  /// The launcher's jar for `classpath`, the test task's: none when it has a launcher already, or
  /// no platform.
  static List<File> missingFrom(Project project, Iterable<File> classpath) {
    return engineVersion(classpath).map(version -> resolved(project, version)).orElse(List.of());
  }

  /// The platform engine's version, when the classpath has it and no launcher.
  static Optional<String> engineVersion(Iterable<File> classpath) {
    String version = null;
    for (File file : classpath) {
      var name = file.getName();
      if (name.startsWith("junit-platform-launcher-")) {
        return Optional.empty();
      }
      var engine = ENGINE.matcher(name);
      if (engine.matches()) {
        version = engine.group("version");
      }
    }
    return Optional.ofNullable(version);
  }

  private static List<File> resolved(Project project, String version) {
    var launcher =
        project
            .getConfigurations()
            .detachedConfiguration(
                project
                    .getDependencies()
                    .create("org.junit.platform:junit-platform-launcher:" + version));
    launcher.setTransitive(false);
    try {
      return List.copyOf(launcher.getFiles());
    } catch (ResolveException e) {
      project.getLogger().warn("mutastic: no junit-platform-launcher {} to resolve", version, e);
      return List.of();
    }
  }
}
