package io.mutastic.gradle.plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import org.gradle.jvm.tasks.Jar;
import org.jspecify.annotations.Nullable;

/// What the `test` task would run the module's tests with (its JDK, JVM arguments, working
/// directory, classpath, which tests and environment) and the module's own directories, written as
/// mutastic's setup file. The same as the init script's `mutasticSetup`, which the `mutastic`
/// command adds to a build from outside it.
public abstract class WriteSetup extends DefaultTask {
  private static final String SEPARATOR = File.pathSeparator;

  private @Nullable SourceSet main;
  private @Nullable SourceSet test;
  private @Nullable TaskProvider<Test> testTask;
  private @Nullable Jar jar;
  private String projectPath = ":";

  @OutputFile
  public abstract RegularFileProperty getSetup();

  static void configure(WriteSetup task) {
    var project = task.getProject();
    var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
    task.main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    task.test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
    task.testTask = project.getTasks().named("test", Test.class);
    task.jar = (Jar) project.getTasks().findByName("jar");
    task.projectPath = project.getPath();
    task.dependsOn(task.main.getClassesTaskName(), task.test.getClassesTaskName());
    task.getSetup().set(project.getLayout().getBuildDirectory().file("mutastic/setup.properties"));
    task.setDescription("Writes what the test task would run the tests with, for mutastic");
    // It reads the test task as it runs, which the configuration cache can't keep.
    task.notCompatibleWithConfigurationCache("reads the test task as it runs");
    task.doNotTrackState("the test task's settings may change without its inputs changing");
  }

  @TaskAction
  public void write() {
    var main = Objects.requireNonNull(this.main);
    var test = Objects.requireNonNull(this.test);
    var task = Objects.requireNonNull(testTask).get();
    var setup = new Properties();
    setup.setProperty("java", task.getJavaLauncher().get().getExecutablePath().toString());
    setup.setProperty("jvmArgs", String.join("\n", task.getAllJvmArgs()));
    setup.setProperty("workingDirectory", task.getWorkingDir().getAbsolutePath());
    directories(setup, main, test);
    setup.setProperty("dependencies", dependencies(task, main, test));
    setup.setProperty("outputs", paths(outputs(main)));
    selection(setup, task);
    setup.setProperty("environment", environment(task));
    store(setup);
  }

  /// The module's own classes, resources and sources.
  private static void directories(Properties setup, SourceSet main, SourceSet test) {
    setup.setProperty("classes", paths(main.getOutput().getClassesDirs().getFiles()));
    setup.setProperty("resources", paths(resources(main)));
    setup.setProperty("testClasses", paths(test.getOutput().getClassesDirs().getFiles()));
    setup.setProperty("testResources", paths(resources(test)));
    setup.setProperty("sources", paths(main.getJava().getSrcDirs()));
    setup.setProperty("testSources", paths(test.getJava().getSrcDirs()));
  }

  /// The test classpath without the module's own directories: the worker loads those itself, so
  /// mutants can replace them.
  private static String dependencies(Test task, SourceSet main, SourceSet test) {
    var own = new LinkedHashSet<File>(main.getOutput().getClassesDirs().getFiles());
    own.addAll(test.getOutput().getClassesDirs().getFiles());
    own.addAll(resources(main));
    own.addAll(resources(test));
    var owned = own.stream().map(File::getAbsolutePath).collect(Collectors.toSet());
    return task.getClasspath().getFiles().stream()
        .map(File::getAbsolutePath)
        .filter(path -> !owned.contains(path))
        .collect(Collectors.joining(SEPARATOR));
  }

  /// What another module sees of this one: taken off its classpath when its tests run over this
  /// module's mutants, so the mutated classes are the ones loaded.
  private List<File> outputs(SourceSet main) {
    var outputs = new ArrayList<File>(main.getOutput().getClassesDirs().getFiles());
    outputs.addAll(resources(main));
    if (jar != null) {
      outputs.add(jar.getArchiveFile().get().getAsFile());
    }
    return outputs;
  }

  /// Which tests the task runs, so mutastic runs the same ones.
  private static void selection(Properties setup, Test task) {
    setup.setProperty("testIncludes", String.join("\n", task.getIncludes()));
    setup.setProperty("testExcludes", String.join("\n", task.getExcludes()));
    var platform = task.getOptions() instanceof JUnitPlatformOptions options ? options : null;
    setup.setProperty(
        "includeTags", platform == null ? "" : String.join("\n", platform.getIncludeTags()));
    setup.setProperty(
        "excludeTags", platform == null ? "" : String.join("\n", platform.getExcludeTags()));
  }

  /// The task's environment starts as Gradle's own: only what it sets beyond that.
  private static String environment(Test task) {
    var inherited = System.getenv();
    return task.getEnvironment().entrySet().stream()
        .filter(entry -> !String.valueOf(entry.getValue()).equals(inherited.get(entry.getKey())))
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  private static Set<File> resources(SourceSet sourceSet) {
    var resources = sourceSet.getOutput().getResourcesDir();
    return resources == null ? Set.of() : Set.of(resources);
  }

  private static String paths(Collection<File> files) {
    return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(SEPARATOR));
  }

  private void store(Properties setup) {
    var file = getSetup().get().getAsFile().toPath();
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream out = Files.newOutputStream(file)) {
        setup.store(out, "mutastic setup for " + projectPath);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
