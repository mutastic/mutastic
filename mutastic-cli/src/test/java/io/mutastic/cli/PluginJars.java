package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/// Plugin jars as a team would ship them: one class, compiled against mutastic's API, and the
/// service file that names it.
final class PluginJars {
  private PluginJars() {}

  /// `jar`, holding `className` (`acme.ConstantPlusOne`) compiled from `source`, as a `service`.
  static void write(Path jar, Class<?> service, String className, String source) throws Exception {
    var sources = Files.createTempDirectory("plugin");
    var file = sources.resolve(className.replace('.', '/') + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    int compiled =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-cp",
                System.getProperty("java.class.path"),
                "-d",
                sources.toString(),
                file.toString());
    assertThat(compiled).isZero();
    Files.createDirectories(jar.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      var classFile = className.replace('.', '/') + ".class";
      out.putNextEntry(new JarEntry(classFile));
      out.write(Files.readAllBytes(sources.resolve(classFile)));
      out.putNextEntry(new JarEntry("META-INF/services/" + service.getName()));
      out.write((className + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }
}
