package io.mutastic.maven.extension;

import java.nio.file.Path;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.toolchain.ToolchainManager;

/// The `java` Surefire runs the tests with: its `jvm` setting, else the build's JDK toolchain, else
/// the JDK Maven itself runs on.
final class Java {
  private Java() {}

  static String of(Surefire surefire, MavenSession session, ToolchainManager toolchains)
      throws Exception {
    var jvm = surefire.value("jvm");
    if (jvm != null) {
      return jvm;
    }
    var jdk = toolchains.getToolchainFromBuildContext("jdk", session);
    var fromToolchain = jdk == null ? null : jdk.findTool("java");
    return fromToolchain != null
        ? fromToolchain
        : Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }
}
