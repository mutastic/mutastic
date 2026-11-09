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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import org.gradle.api.tasks.testing.testng.TestNGOptions;
import org.gradle.jvm.tasks.Jar;

/// What the suite's `Test` task (`test`, or `integrationTest` say) would run the module's tests
/// with (its JDK, JVM arguments, working directory, classpath, which tests and environment) and the
/// module's own directories, written as mutastic's setup file. The same as the init script's
/// `mutasticSetup`, which the `mutastic` command adds to a build from outside it.
///
/// Everything it reads is captured as the build is configured, in values and file collections
/// rather than the `Test` task itself, so the configuration cache can keep it and the task never
/// reaches into the project as it runs.
public abstract class WriteSetup extends DefaultTask {
  private static final String SEPARATOR = File.pathSeparator;

  @OutputFile
  public abstract RegularFileProperty getSetup();

  /// The `Test` task whose tests run.
  @Input
  public abstract Property<String> getSuite();

  /// What the test task runs, as the setup file names each: `java`, `workingDirectory`.
  @Input
  public abstract MapProperty<String, String> getValues();

  /// The test task's JVM arguments.
  @Input
  public abstract ListProperty<String> getJvmArgs();

  /// The module, as Gradle names it: `:` or `:core`.
  @Input
  public abstract Property<String> getProjectPath();

  /// The module's main classes and resources, and the test source set's.
  @Internal
  public abstract ConfigurableFileCollection getClasses();

  @Internal
  public abstract ConfigurableFileCollection getResources();

  @Internal
  public abstract ConfigurableFileCollection getTestClasses();

  @Internal
  public abstract ConfigurableFileCollection getTestResources();

  @Internal
  public abstract ConfigurableFileCollection getSources();

  @Internal
  public abstract ConfigurableFileCollection getTestSources();

  /// The test task's classpath.
  @Internal
  public abstract ConfigurableFileCollection getClasspath();

  /// What another module sees of this one: its classes, resources and jar.
  @Internal
  public abstract ConfigurableFileCollection getModuleOutputs();

  /// What the test classpath has of the build's other modules.
  @Internal
  public abstract ConfigurableFileCollection getModules();

  /// Quarkus's pre-built application model, when its plugin is there.
  @Internal
  public abstract ConfigurableFileCollection getQuarkusModel();

  /// Set up once [#getSuite] is set: it names the test task, whose classpath it builds first.
  static void configure(WriteSetup task) {
    var project = task.getProject();
    var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
    var main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    var testTask = project.getTasks().withType(Test.class).named(task.getSuite().get(), Test.class);
    // The test task, read without depending on it: a value mapped from its TaskProvider makes
    // this task depend on the test task, so the tests would run before the setup is written.
    Provider<Test> suite = project.provider(testTask::get);
    directories(task, project, main, suite.map(test -> testsOf(sourceSets, test)));
    task.getClasspath().from(suite.map(Test::getClasspath));
    task.getValues().putAll(suite.map(WriteSetup::values));
    task.getJvmArgs().set(suite.map(Test::getAllJvmArgs));
    task.getProjectPath().set(project.getPath());
    quarkus(task, project);
    // Everything on the test task's classpath is built, as the test task would build it: another
    // module's jar, on a fresh checkout, as well as this module's classes.
    task.dependsOn(
        main.getClassesTaskName(),
        suite.map(Test::getTestClassesDirs),
        suite.map(Test::getClasspath));
    task.getSetup().set(project.getLayout().getBuildDirectory().file("mutastic/setup.properties"));
    task.setDescription("Writes what the test task would run the tests with, for mutastic");
    task.doNotTrackState("the test task's settings may change without its inputs changing");
  }

  /// The module's own directories, and the test source set's: `tests`.
  private static void directories(
      WriteSetup task, Project project, SourceSet main, Provider<SourceSet> tests) {
    task.getClasses().from(main.getOutput().getClassesDirs());
    task.getResources().from(resources(project, main));
    task.getSources().from(project.provider(() -> sourceDirs(main)));
    task.getTestClasses().from(tests.map(test -> test.getOutput().getClassesDirs()));
    task.getTestResources().from(tests.map(test -> resources(project, test)));
    task.getTestSources().from(tests.map(WriteSetup::sourceDirs));
    task.getModules().from(tests.map(test -> modules(project, test)));
    task.getModuleOutputs().from(task.getClasses(), task.getResources());
    if (project.getTasks().getNames().contains("jar")) {
      var jar = project.getTasks().named("jar", Jar.class);
      task.getModuleOutputs().from(jar.flatMap(Jar::getArchiveFile));
    }
  }

  /// Quarkus's plugin hands its Test task a pre-built application model as the task runs, so it
  /// isn't among the task's JVM arguments. Without it, every @QuarkusTest asks Gradle for the model
  /// again.
  private static void quarkus(WriteSetup task, Project project) {
    if (project.getTasks().getNames().contains("quarkusGenerateTestAppModel")) {
      var model = project.getTasks().named("quarkusGenerateTestAppModel");
      task.getQuarkusModel().from(model.map(each -> each.getOutputs().getFiles()));
      task.dependsOn(model);
    }
  }

  /// The single values the setup file holds, read from the test task.
  private static Map<String, String> values(Test test) {
    var values = new java.util.TreeMap<String, String>();
    values.put("java", test.getJavaLauncher().get().getExecutablePath().toString());
    values.put("workingDirectory", test.getWorkingDir().getAbsolutePath());
    values.put("testIncludes", String.join("\n", test.getIncludes()));
    values.put("testExcludes", String.join("\n", test.getExcludes()));
    values.putAll(tags(test));
    values.put("environment", environment(test));
    return values;
  }

  @TaskAction
  public void write() {
    var setup = new Properties();
    getValues().get().forEach(setup::setProperty);
    setup.setProperty("jvmArgs", String.join("\n", jvmArgs()));
    setup.setProperty("classes", paths(getClasses().getFiles()));
    setup.setProperty("resources", paths(getResources().getFiles()));
    setup.setProperty("testClasses", paths(getTestClasses().getFiles()));
    setup.setProperty("testResources", paths(getTestResources().getFiles()));
    setup.setProperty("sources", paths(getSources().getFiles()));
    setup.setProperty("testSources", paths(getTestSources().getFiles()));
    var modules =
        getModules().getFiles().stream().map(File::getAbsolutePath).collect(Collectors.toSet());
    setup.setProperty("dependencies", classpath(path -> !modules.contains(path)));
    setup.setProperty("alongside", classpath(modules::contains));
    setup.setProperty("outputs", paths(getModuleOutputs().getFiles()));
    store(setup, getProjectPath().get());
  }

  /// The source set whose classes the test task runs: `test` for `test`, `integrationTest` for a
  /// suite of that name.
  private static SourceSet testsOf(SourceSetContainer sourceSets, Test task) {
    var runs = task.getTestClassesDirs().getFiles();
    return sourceSets.stream()
        .filter(set -> !set.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME))
        .filter(
            set -> set.getOutput().getClassesDirs().getFiles().stream().anyMatch(runs::contains))
        .findFirst()
        .orElseThrow(
            () ->
                new GradleException(
                    "mutastic: no source set holds the classes %s runs".formatted(task.getName())));
  }

  /// The test task's JVM arguments, with Quarkus's application model when there is one.
  private List<String> jvmArgs() {
    var arguments = new ArrayList<>(getJvmArgs().get());
    getQuarkusModel().getFiles().stream()
        .filter(file -> file.getName().endsWith("test-model.dat"))
        .findFirst()
        .ifPresent(
            model ->
                arguments.add(
                    "-Dquarkus-internal-test.serialized-app-model.path="
                        + model.getAbsolutePath()));
    return arguments;
  }

  /// The test classpath without the module's own directories, as `chosen` picks from it: the worker
  /// loads those itself, so mutants can replace them.
  private String classpath(Predicate<String> chosen) {
    var own = new LinkedHashSet<File>(getClasses().getFiles());
    own.addAll(getTestClasses().getFiles());
    own.addAll(getResources().getFiles());
    own.addAll(getTestResources().getFiles());
    var owned = own.stream().map(File::getAbsolutePath).collect(Collectors.toSet());
    return getClasspath().getFiles().stream()
        .map(File::getAbsolutePath)
        .filter(path -> !owned.contains(path) && chosen.test(path))
        .collect(Collectors.joining(SEPARATOR));
  }

  /// What `tests`' runtime classpath has of the build's modules, this one's test fixtures among
  /// them. The worker loads them with the module's own classes: one may look this module's classes
  /// up by name (`Class.forName`), as it can on the test task's one classpath.
  private static FileCollection modules(Project project, SourceSet tests) {
    return project
        .getConfigurations()
        .getByName(tests.getRuntimeClasspathConfigurationName())
        .getIncoming()
        .artifactView(view -> view.componentFilter(id -> id instanceof ProjectComponentIdentifier))
        .getFiles();
  }

  /// Which tests the task runs by tag, so mutastic runs the same ones. TestNG's groups are tags on
  /// the platform, as its engine runs them.
  private static Map<String, String> tags(Test task) {
    Set<String> include = Set.of();
    Set<String> exclude = Set.of();
    if (task.getOptions() instanceof JUnitPlatformOptions platform) {
      include = platform.getIncludeTags();
      exclude = platform.getExcludeTags();
    } else if (task.getOptions() instanceof TestNGOptions testng) {
      include = testng.getIncludeGroups();
      exclude = testng.getExcludeGroups();
    }
    return Map.of(
        "includeTags", String.join("\n", include), "excludeTags", String.join("\n", exclude));
  }

  /// The task's environment starts as Gradle's own: only what it sets beyond that.
  private static String environment(Test task) {
    var inherited = System.getenv();
    return task.getEnvironment().entrySet().stream()
        .filter(entry -> !String.valueOf(entry.getValue()).equals(inherited.get(entry.getKey())))
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  /// A source set's resource directory, when it has one.
  private static Provider<Set<File>> resources(Project project, SourceSet sourceSet) {
    return project.provider(
        () -> {
          var resources = sourceSet.getOutput().getResourcesDir();
          return resources == null ? Set.<File>of() : Set.of(resources);
        });
  }

  private static String paths(Collection<File> files) {
    return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(SEPARATOR));
  }

  private void store(Properties setup, String projectPath) {
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

  /// A set's source directories: Java's, and Kotlin's when its plugin is there.
  private static Set<File> sourceDirs(SourceSet set) {
    var directories = new LinkedHashSet<>(set.getJava().getSrcDirs());
    if (set.getExtensions().findByName("kotlin")
        instanceof org.gradle.api.file.SourceDirectorySet kotlin) {
      directories.addAll(kotlin.getSrcDirs());
    }
    return directories;
  }
}
