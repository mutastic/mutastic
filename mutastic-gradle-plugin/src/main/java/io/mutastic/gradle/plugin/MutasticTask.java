package io.mutastic.gradle.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

/// One of mutastic's commands, run on the module's setup by the `mutastic` command's own main, on
/// JDK 25, in a process of its own. The command prints its own output, with the commands it offers
/// written as these tasks; the task fails when the command does.
///
/// How a run happens is set here, each property taking the `mutastic { }` extension's as its
/// convention, and a `-Pmutastic.<name>` on the command line wins over both, for one run.
@UntrackedTask(because = "runs the tests each time, as the test task would")
public abstract class MutasticTask extends DefaultTask {
  /// Why the build fails when what the command checks doesn't hold.
  private static final Map<String, String> FAILURES =
      Map.of(
          "run", "the mutation score is below the threshold",
          "verify", "the mutant isn't caught: its tests still pass with it in",
          "explain", "the mutant isn't caught",
          "ignore", "mutastic couldn't record the ignore");

  /// `run`: the command, as the `mutastic` command names it.
  @Input
  public abstract Property<String> getCommand();

  @InputFile
  @PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract RegularFileProperty getSetup();

  @Nested
  public abstract Property<JavaLauncher> getJavaLauncher();

  /// See [MutasticExtension] for each.
  @Input
  @Optional
  public abstract Property<Integer> getThreshold();

  @Input
  public abstract ListProperty<String> getTestsFrom();

  @Input
  @Optional
  public abstract Property<String> getSuite();

  @Input
  @Optional
  public abstract Property<Integer> getWorkers();

  @Input
  public abstract ListProperty<String> getReports();

  @Input
  @Optional
  public abstract Property<String> getFormat();

  @Input
  @Optional
  public abstract Property<String> getSince();

  @Input
  @Optional
  public abstract Property<Boolean> getConfirmCatches();

  @Input
  @Optional
  public abstract Property<Boolean> getFailOnNew();

  @Input
  @Optional
  public abstract Property<Boolean> getSuggestions();

  @Input
  @Optional
  public abstract Property<Boolean> getEachMutant();

  @Classpath
  public abstract ConfigurableFileCollection getCommandJars();

  /// The build's root directory, and the module as Gradle names it: `:` or `:core`.
  @Input
  public abstract Property<String> getRoot();

  @Input
  public abstract Property<String> getModule();

  /// Where Gradle was run from, so the commands offered work from there.
  @Input
  public abstract Property<String> getHere();

  /// How the build is run: `./gradlew`, or `gradle` without a wrapper.
  @Input
  public abstract Property<String> getGradle();

  /// The `mutastic.` properties on the command line, `mutastic.threshold` → `60`: over what's set
  /// here, for one run, and passed on as they are.
  @Input
  public abstract MapProperty<String, String> getCommandLine();

  @Inject
  protected abstract ExecOperations getExec();

  static void configure(MutasticTask task, Project project) {
    var root = project.getRootDir();
    var here = project.getGradle().getStartParameter().getCurrentDir();
    task.getRoot().set(root.getAbsolutePath());
    task.getModule().set(project.getPath());
    task.getHere().set(here.getAbsolutePath());
    task.getGradle().set(MutasticPlugin.gradle(root, here));
    task.getCommandLine().set(project.getProviders().gradlePropertiesPrefixedBy("mutastic."));
    task.setGroup("verification");
    task.setDescription(
        "Runs `mutastic %s` on this module's code and tests".formatted(task.getCommand().get()));
  }

  @TaskAction
  public void run() {
    var result =
        getExec()
            .javaexec(
                spec -> {
                  spec.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile());
                  spec.classpath(getCommandJars());
                  spec.getMainClass().set("io.mutastic.cli.Main");
                  spec.args(arguments());
                  spec.setWorkingDir(new File(getHere().get()));
                  spec.setIgnoreExitValue(true);
                });
    int exit = result.getExitValue();
    var command = getCommand().get();
    if (exit == 1) {
      throw new GradleException(failure(command));
    }
    if (exit != 0) {
      throw new GradleException(
          "mutastic %s stopped (exit %d): what went wrong is above".formatted(command, exit));
    }
  }

  /// Why the build fails when what the command checks doesn't hold: for a run with `failOnNew`, a
  /// new miss, not its score.
  private String failure(String command) {
    var failOnNew = settings(configured(), getCommandLine().get()).get("failOnNew");
    if (command.equals("run") && failOnNew != null && !failOnNew.equals("false")) {
      return "a mutant no test catches isn't in .mutastic/baseline.yaml";
    }
    return FAILURES.getOrDefault(command, "mutastic " + command + " failed");
  }

  /// Each property's convention is the extension's.
  void from(MutasticExtension extension) {
    getThreshold().convention(extension.getThreshold());
    getTestsFrom().convention(extension.getTestsFrom());
    getSuite().convention(extension.getSuite());
    getWorkers().convention(extension.getWorkers());
    getReports().convention(extension.getReports());
    getFormat().convention(extension.getFormat());
    getSince().convention(extension.getSince());
    getConfirmCatches().convention(extension.getConfirmCatches());
    getFailOnNew().convention(extension.getFailOnNew());
    getSuggestions().convention(extension.getSuggestions());
    getEachMutant().convention(extension.getEachMutant());
    getJavaLauncher().convention(extension.getJavaLauncher());
  }

  /// What's set here, by name: `threshold` → `80`, lists joined by commas.
  Map<String, String> configured() {
    var configured = new TreeMap<String, String>();
    put(configured, "threshold", getThreshold());
    put(configured, "workers", getWorkers());
    put(configured, "format", getFormat());
    put(configured, "since", getSince());
    put(configured, "suite", getSuite());
    put(configured, "confirmCatches", getConfirmCatches());
    put(configured, "failOnNew", getFailOnNew());
    put(configured, "suggestions", getSuggestions());
    put(configured, "eachMutant", getEachMutant());
    configured.put("testsFrom", String.join(",", getTestsFrom().get()));
    configured.put("reports", String.join(",", getReports().get()));
    return configured;
  }

  private static void put(Map<String, String> configured, String name, Provider<?> value) {
    if (value.isPresent()) {
      configured.put(name, String.valueOf(value.get()));
    }
  }

  /// What the run uses: `configured`, with the command line's `mutastic.` properties over it. A
  /// list given there replaces the configured one whole.
  static Map<String, String> settings(
      Map<String, String> configured, Map<String, String> commandLine) {
    var settings = new TreeMap<>(configured);
    commandLine.forEach((name, value) -> settings.put(name.substring("mutastic.".length()), value));
    return settings;
  }

  /// `<command> -Pmutastic.threshold=60 <root> :core --setup=… --commands=gradle:./gradlew`.
  List<String> arguments() {
    var arguments = new ArrayList<String>();
    arguments.add(getCommand().get());
    settings(configured(), getCommandLine().get())
        .forEach((name, value) -> arguments.add("-Pmutastic." + name + "=" + value));
    arguments.add(getRoot().get());
    if (!getModule().get().equals(":")) {
      arguments.add(getModule().get());
    }
    arguments.add("--setup=" + getSetup().get().getAsFile().getAbsolutePath());
    arguments.add("--commands=gradle:" + getGradle().get());
    return arguments;
  }
}
