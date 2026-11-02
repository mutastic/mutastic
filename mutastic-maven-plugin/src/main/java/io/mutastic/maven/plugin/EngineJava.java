package io.mutastic.maven.plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.jspecify.annotations.Nullable;

/// The `java` mutastic runs on: JDK 25 or later, for the ClassFile API. The project's tests still
/// run on the project's own JDK, as Surefire would run them; this is only mutastic's.
final class EngineJava {
  static final int NEEDED = 25;

  private EngineJava() {}

  /// `jdk` if it's set (`-Dmutastic.jdk`), else a JDK toolchain of 25 or later, else the JDK Maven
  /// runs on, if it's one.
  static Path find(@Nullable File jdk, ToolchainManager toolchains, MavenSession session)
      throws MojoExecutionException {
    if (jdk != null) {
      return named(jdk.toPath());
    }
    for (Toolchain toolchain :
        toolchains.getToolchains(session, "jdk", Map.of("version", "[" + NEEDED + ",)"))) {
      var java = toolchain.findTool("java");
      if (java != null) {
        return Path.of(java);
      }
    }
    if (Runtime.version().feature() >= NEEDED) {
      return Path.of(System.getProperty("java.home"), "bin", "java");
    }
    throw new MojoExecutionException(
        ("mutastic runs on JDK %d or later, and Maven runs on %d. Add a JDK %d to"
                + " ~/.m2/toolchains.xml, run Maven on one, or name one: -Dmutastic.jdk=<its"
                + " directory>")
            .formatted(NEEDED, Runtime.version().feature(), NEEDED));
  }

  private static Path named(Path jdk) throws MojoExecutionException {
    var java = jdk.resolve("bin").resolve("java");
    if (!Files.isExecutable(java)) {
      throw new MojoExecutionException(
          "mutastic.jdk names %s, which has no bin/java: name a JDK's directory".formatted(jdk));
    }
    return java;
  }
}
