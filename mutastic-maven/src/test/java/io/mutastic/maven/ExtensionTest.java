package io.mutastic.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.JarURLConnection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/// The extension mutastic loads into a project's own Maven, as it ships.
class ExtensionTest {
  /// The newest class file Sisu 0.3 reads: Java 14's. Maven before 3.9.6 finds an extension's
  /// components with it, and passes over a newer class file without a word.
  private static final int NEWEST_SISU_0_3_READS = 58;

  @Test
  void everyClassLoadsInMavenBefore396() throws IOException {
    assertThat(majorVersions())
        .isNotEmpty()
        .allSatisfy(
            (name, major) -> assertThat(major).as(name).isLessThanOrEqualTo(NEWEST_SISU_0_3_READS));
  }

  /// Each class file in the extension's jar, with its major version.
  private static Map<String, Integer> majorVersions() throws IOException {
    var classFile = ExtensionTest.class.getClassLoader().getResource(MavenSetup.EXTENSION_CLASS);
    assertThat(classFile).as("the extension's jar is on the class path").isNotNull();
    var connection = (JarURLConnection) classFile.openConnection();
    connection.setUseCaches(false);
    var majors = new LinkedHashMap<String, Integer>();
    try (JarFile jar = connection.getJarFile()) {
      for (var entry : Collections.list(jar.entries())) {
        if (!entry.getName().endsWith(".class")) {
          continue;
        }
        try (var in = new DataInputStream(jar.getInputStream(entry))) {
          in.readInt(); // 0xCAFEBABE
          in.readUnsignedShort(); // the minor version
          majors.put(entry.getName(), in.readUnsignedShort());
        }
      }
    }
    return majors;
  }
}
