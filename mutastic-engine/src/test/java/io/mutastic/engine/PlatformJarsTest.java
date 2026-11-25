package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What stays on the worker's JDK class path: the JUnit Platform's launcher and what it needs, by
/// what a jar holds, never a library or a directory.
class PlatformJarsTest {
  @TempDir Path temp;

  @Test
  void aJarOfThePlatformsPackagesStaysWhateverItsName() throws Exception {
    assertThat(
            PlatformJars.holdsThePlatform(
                jar("renamed-launcher.jar", "org/junit/platform/launcher/Launcher.class")))
        .isTrue();
    assertThat(
            PlatformJars.holdsThePlatform(
                jar("ot.jar", "org/opentest4j/AssertionFailedError.class")))
        .isTrue();
  }

  @Test
  void aLibraryOrADirectoryIsTheSystemLoaders() throws Exception {
    assertThat(
            PlatformJars.holdsThePlatform(
                jar(
                    "junit-jupiter-engine.jar",
                    "org/junit/jupiter/engine/JupiterTestEngine.class")))
        .isFalse();
    assertThat(
            PlatformJars.holdsThePlatform(
                jar("spring-core.jar", "org/springframework/util/SerializationUtils.class")))
        .isFalse();
    assertThat(PlatformJars.holdsThePlatform(Files.createDirectories(temp.resolve("classes"))))
        .isFalse();
  }

  private Path jar(String name, String entry) throws Exception {
    var jar = temp.resolve(name);
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry(entry));
      out.write(new byte[] {0});
    }
    return jar;
  }
}
