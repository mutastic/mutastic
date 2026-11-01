package io.mutastic.maven.extension;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.project.MavenProject;

/// One module's setup file, in the format every mutastic build tool writes. Maven copies resources
/// into the class directories, so there are no resource directories of their own.
final class ModuleSetup {
  /// Surefire's `@{name}`: a property read when the tests start, after plugins like JaCoCo set it.
  private static final Pattern LATE = Pattern.compile("@\\{(?<name>[^}]+)}");

  private static final Pattern WORDS = Pattern.compile("\\s+");

  private final MavenProject project;
  private final Surefire surefire;
  private final Launcher launcher;

  ModuleSetup(MavenProject project, Surefire surefire, Launcher launcher) {
    this.project = project;
    this.surefire = surefire;
    this.launcher = launcher;
  }

  void write(Path file, String java) throws Exception {
    var build = project.getBuild();
    var setup = new Properties();
    setup.put("java", java);
    setup.put("jvmArgs", String.join("\n", jvmArgs()));
    var workingDirectory = surefire.value("workingDirectory");
    setup.put(
        "workingDirectory",
        workingDirectory != null ? workingDirectory : project.getBasedir().getPath());
    setup.put("classes", build.getOutputDirectory());
    setup.put("resources", "");
    setup.put("testClasses", build.getTestOutputDirectory());
    setup.put("testResources", "");
    setup.put("sources", joined(project.getCompileSourceRoots()));
    setup.put("testSources", joined(project.getTestCompileSourceRoots()));
    setup.put("dependencies", joined(dependencies()));
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file)) {
      setup.store(out, "mutastic setup for " + project.getId());
    }
  }

  /// The test classpath without the module's own directories, which the worker loads itself.
  private List<String> dependencies() throws Exception {
    var build = project.getBuild();
    var own = Set.of(build.getOutputDirectory(), build.getTestOutputDirectory());
    var dependencies = new ArrayList<String>();
    for (String element : project.getTestClasspathElements()) {
      if (!own.contains(element)) {
        dependencies.add(element);
      }
    }
    launcher.addTo(dependencies);
    return dependencies;
  }

  /// `argLine`, with its late `@{…}` properties, then each system property as `-Dname=value`.
  private List<String> jvmArgs() throws Exception {
    var args = new ArrayList<String>();
    var argLine = surefire.value("argLine");
    if (argLine != null) {
      WORDS.splitAsStream(late(argLine).strip()).filter(arg -> !arg.isEmpty()).forEach(args::add);
    }
    surefire
        .map("systemPropertyVariables")
        .forEach((name, value) -> args.add("-D" + name + "=" + value));
    return args;
  }

  private String late(String text) {
    Matcher property = LATE.matcher(text);
    var out = new StringBuilder();
    while (property.find()) {
      var value = project.getProperties().getProperty(property.group("name"), "");
      property.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    property.appendTail(out);
    return out.toString();
  }

  private static String joined(List<String> paths) {
    return String.join(File.pathSeparator, paths);
  }
}
