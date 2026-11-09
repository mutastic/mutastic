package io.mutastic.maven.extension;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;

/// One module's setup file, in the format every mutastic build tool writes. Maven copies resources
/// into the class directories, so there are no resource directories of their own.
final class ModuleSetup {
  /// Surefire's `@{name}`: a property read when the tests start, after plugins like JaCoCo set it.
  private static final Pattern LATE = Pattern.compile("@\\{(?<name>[^}]+)}");

  private static final Pattern WORDS = Pattern.compile("\\s+");

  private static final Pattern COMMA = Pattern.compile(",");

  private final MavenProject project;
  private final Surefire surefire;
  private final Launcher launcher;

  /// Whether the settings are Failsafe's, whose tests are named for integration tests.
  private final boolean failsafe;

  ModuleSetup(MavenProject project, Surefire surefire, Launcher launcher, boolean failsafe) {
    this.project = project;
    this.surefire = surefire;
    this.launcher = launcher;
    this.failsafe = failsafe;
  }

  /// Writes the setup to `file`, `java` running the tests, with the `reactor`'s modules.
  void write(Path file, String java, Collection<MavenProject> reactor) throws Exception {
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
    putClasspath(setup, reactor);
    setup.put("outputs", joined(outputs(project)));
    putTests(setup);
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file)) {
      setup.store(out, "mutastic setup for " + project.getId());
    }
  }

  /// Surefire's defaults, when a build names no includes of its own.
  private static final List<String> DEFAULT_INCLUDES =
      List.of("**/Test*.class", "**/*Test.class", "**/*Tests.class", "**/*TestCase.class");

  /// Failsafe's.
  private static final List<String> INTEGRATION_INCLUDES =
      List.of("**/IT*.class", "**/*IT.class", "**/*ITCase.class");

  /// Which tests Surefire runs, and the environment it gives them.
  private void putTests(Properties setup) throws Exception {
    var includes = patterns(surefire.list("includes"));
    var defaults = failsafe ? INTEGRATION_INCLUDES : DEFAULT_INCLUDES;
    setup.put("testIncludes", lines(includes.isEmpty() ? defaults : includes));
    var excludes = patterns(surefire.list("excludes"));
    setup.put("testExcludes", lines(excludes.isEmpty() ? List.of("**/*$*") : excludes));
    setup.put("includeTags", lines(commaSeparated(surefire.value("groups"))));
    setup.put("excludeTags", lines(commaSeparated(surefire.value("excludedGroups"))));
    var environment = new ArrayList<String>();
    surefire
        .map("environmentVariables")
        .forEach((name, value) -> environment.add(name + "=" + value));
    setup.put("environment", lines(environment));
  }

  /// Surefire's patterns as class-file patterns: `**/*Test.java` and `**/*Test` become
  /// `**/*Test.class`, a pattern with no directory matches in any, and `%regex[…]` is a regex.
  private static List<String> patterns(List<String> surefirePatterns) {
    var patterns = new ArrayList<String>();
    for (String pattern : surefirePatterns) {
      for (String each : COMMA.splitAsStream(pattern).toList()) {
        var trimmed = each.strip();
        if (trimmed.startsWith("%regex[") && trimmed.endsWith("]")) {
          patterns.add("regex:" + trimmed.substring("%regex[".length(), trimmed.length() - 1));
        } else if (!trimmed.isEmpty()) {
          var classFile = trimmed.replaceAll("\\.java$", "").replaceAll("\\.class$", "") + ".class";
          patterns.add(classFile.contains("/") ? classFile : "**/" + classFile);
        }
      }
    }
    return patterns;
  }

  private static List<String> commaSeparated(@Nullable String value) {
    return value == null
        ? List.of()
        : COMMA.splitAsStream(value).map(String::strip).filter(tag -> !tag.isEmpty()).toList();
  }

  private static String lines(List<String> values) {
    return String.join("\n", values);
  }

  /// The test classpath, split: the reactor's other modules on it go `alongside`, loaded by the
  /// worker with the module's own classes, since one may look this module's classes up by name
  /// (`Class.forName`), as it can on Surefire's one classpath; the rest are `dependencies`.
  private void putClasspath(Properties setup, Collection<MavenProject> reactor) throws Exception {
    var modules =
        reactor.stream()
            .flatMap(module -> outputs(module).stream())
            .collect(Collectors.toUnmodifiableSet());
    var classpath = dependencies();
    var others = classpath.stream().filter(path -> !modules.contains(path)).toList();
    setup.put("dependencies", joined(others));
    setup.put("alongside", joined(classpath.stream().filter(modules::contains).toList()));
  }

  /// What another module sees of `project`: its classes, or its jar once packaged.
  private static List<String> outputs(MavenProject project) {
    var build = project.getBuild();
    return List.of(
        build.getOutputDirectory(),
        Path.of(build.getDirectory(), build.getFinalName() + ".jar").toString());
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
