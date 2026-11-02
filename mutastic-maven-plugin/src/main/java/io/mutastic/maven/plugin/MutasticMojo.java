package io.mutastic.maven.plugin;

import io.mutastic.maven.extension.MutasticSetup;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;
import org.jspecify.annotations.Nullable;

/// What every goal shares: writes the module's setup, as Surefire's `test` would run it, then runs
/// mutastic's command of the same name on it, on JDK 25 or later, in a process of its own. The
/// command prints its own output, with the commands it offers written as this plugin's goals. Its
/// options are the `mutastic.` properties (`-Dmutastic.threshold=60`), passed on as they are.
abstract class MutasticMojo extends AbstractMojo {
  /// Properties the goal reads itself, not passed on.
  private static final List<String> OWN = List.of("mutastic.jdk", "mutastic.skip");

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
  private PluginDescriptor plugin;

  /// A JDK 25 or later to run mutastic on, when no JDK toolchain is one and Maven's own JDK isn't.
  @Parameter(property = "mutastic.jdk")
  private @Nullable File jdk;

  @Parameter(property = "mutastic.skip", defaultValue = "false")
  private boolean skip;

  private final LifecycleExecutor lifecycle;
  private final ToolchainManager toolchains;
  private final RepositorySystem repositories;

  MutasticMojo(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    this.lifecycle = lifecycle;
    this.toolchains = toolchains;
    this.repositories = repositories;
  }

  /// `run`: the goal's name, and the command's.
  abstract String goal();

  /// Why the build fails when the command finds what it checks for doesn't hold.
  abstract String failure();

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip || project.getPackaging().equals("pom")) {
      getLog().info("mutastic: nothing to mutate in " + project.getArtifactId());
      return;
    }
    var setup = Path.of(project.getBuild().getDirectory(), "mutastic", "setup.properties");
    try {
      new MutasticSetup(lifecycle, toolchains, repositories).write(session, project, setup);
    } catch (Exception e) {
      throw new MojoExecutionException("mutastic couldn't read " + project.getId(), e);
    }
    int exit = run(command(setup));
    if (exit == 1) {
      throw new MojoFailureException(failure());
    }
    if (exit != 0) {
      throw new MojoExecutionException(
          "mutastic %s stopped (exit %d): what went wrong is above".formatted(goal(), exit));
    }
  }

  /// `java -cp <the plugin's classpath> io.mutastic.cli.Main <goal> <properties> <project>`.
  private List<String> command(Path setup) throws MojoExecutionException {
    var command = new ArrayList<String>();
    command.add(EngineJava.find(jdk, toolchains, session).toString());
    command.addAll(List.of("-cp", classpath(), "io.mutastic.cli.Main", goal()));
    command.addAll(properties());
    var root = Path.of(session.getExecutionRootDirectory());
    command.add(root.toString());
    var module = root.relativize(project.getBasedir().toPath()).toString();
    if (!module.isEmpty()) {
      command.add(":" + module.replace(File.separatorChar, '/'));
    }
    command.add("--setup=" + setup);
    command.add("--commands=" + commands());
    return command;
  }

  /// The plugin's own jars, mutastic's command among them.
  private String classpath() {
    return plugin.getArtifacts().stream()
        .map(Artifact::getFile)
        .map(File::getPath)
        .collect(Collectors.joining(File.pathSeparator));
  }

  /// `-Dmutastic.threshold=60`: each `mutastic.` property from Maven's command line.
  private List<String> properties() {
    return session.getUserProperties().stringPropertyNames().stream()
        .filter(name -> name.startsWith("mutastic.") && !OWN.contains(name))
        .sorted()
        .map(name -> "-D" + name + "=" + session.getUserProperties().getProperty(name))
        .toList();
  }

  /// `maven` when the goals can be named by the plugin's prefix (`mutastic:verify`): the build
  /// declares it, or the settings list its group. Else `maven:` and its coordinates.
  private String commands() {
    var key = plugin.getGroupId() + ":" + plugin.getArtifactId();
    var declared = new ArrayList<Plugin>(project.getBuildPlugins());
    if (project.getPluginManagement() != null) {
      declared.addAll(project.getPluginManagement().getPlugins());
    }
    boolean byPrefix =
        declared.stream().anyMatch(each -> each.getKey().equals(key))
            || session.getSettings().getPluginGroups().contains(plugin.getGroupId());
    return byPrefix ? "maven" : "maven:" + key + ":" + plugin.getVersion();
  }

  /// Runs it where Maven runs, sharing Maven's terminal: its output is as it would be on its own.
  private int run(List<String> command) throws MojoExecutionException {
    try {
      return new ProcessBuilder(command)
          .directory(new File(System.getProperty("user.dir")))
          .inheritIO()
          .start()
          .waitFor();
    } catch (IOException e) {
      throw new MojoExecutionException("mutastic couldn't start: " + command.get(0), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("interrupted while mutastic ran", e);
    }
  }
}
