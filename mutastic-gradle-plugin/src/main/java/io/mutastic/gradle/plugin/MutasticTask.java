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
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

/// One of mutastic's commands, run on the module's setup by the `mutastic` command's own main, on
/// JDK 25, in a process of its own. The command prints its own output, with the commands it offers
/// written as these tasks; the task fails when the command does.
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
  public abstract Property<JavaLauncher> getJava();

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

  /// The `mutastic.` properties: `mutastic.threshold` → `60`.
  @Input
  public abstract MapProperty<String, String> getOptions();

  @Inject
  protected abstract ExecOperations getExec();

  static void configure(MutasticTask task, Project project) {
    var root = project.getRootDir();
    var here = project.getGradle().getStartParameter().getCurrentDir();
    task.getRoot().set(root.getAbsolutePath());
    task.getModule().set(project.getPath());
    task.getHere().set(here.getAbsolutePath());
    task.getGradle().set(MutasticPlugin.gradle(root, here));
    task.getOptions().set(project.getProviders().gradlePropertiesPrefixedBy("mutastic."));
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
                  spec.setExecutable(getJava().get().getExecutablePath().getAsFile());
                  spec.classpath(getCommandJars());
                  spec.getMainClass().set("io.mutastic.cli.Main");
                  spec.args(arguments());
                  spec.setWorkingDir(new File(getHere().get()));
                  spec.setIgnoreExitValue(true);
                });
    int exit = result.getExitValue();
    var command = getCommand().get();
    if (exit == 1) {
      throw new GradleException(FAILURES.getOrDefault(command, "mutastic " + command + " failed"));
    }
    if (exit != 0) {
      throw new GradleException(
          "mutastic %s stopped (exit %d): what went wrong is above".formatted(command, exit));
    }
  }

  /// `<command> -Pmutastic.threshold=60 <root> :core --setup=… --commands=gradle:./gradlew`.
  private List<String> arguments() {
    var arguments = new ArrayList<String>();
    arguments.add(getCommand().get());
    new TreeMap<>(getOptions().get())
        .forEach((name, value) -> arguments.add("-P" + name + "=" + value));
    arguments.add(getRoot().get());
    if (!getModule().get().equals(":")) {
      arguments.add(getModule().get());
    }
    arguments.add("--setup=" + getSetup().get().getAsFile().getAbsolutePath());
    arguments.add("--commands=gradle:" + getGradle().get());
    return arguments;
  }
}
