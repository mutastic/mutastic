package io.mutastic.gradle.plugin;

import java.io.File;
import java.util.List;
import java.util.Locale;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/// `id("io.mutastic")`: in each Java project it's applied to, a task for each of mutastic's
/// commands, `mutasticRun`, `mutasticVerify`, `mutasticExplain` and `mutasticIgnore`, set up by the
/// `mutastic { }` extension and each task's own properties, with `-Pmutastic.<name>` over both for
/// one run.
public abstract class MutasticPlugin implements Plugin<Project> {
  /// The JDK mutastic runs on, for the ClassFile API; a Gradle toolchain, so Gradle finds or
  /// fetches it. The tests still run on the JDK the `test` task uses.
  static final int ENGINE_JDK = 25;

  private static final List<String> COMMANDS = List.of("run", "verify", "explain", "ignore");

  @Override
  public void apply(Project project) {
    var extension = project.getExtensions().create("mutastic", MutasticExtension.class);
    project.getPlugins().withType(JavaPlugin.class, java -> configure(project, extension));
  }

  private static void configure(Project project, MutasticExtension extension) {
    defaults(project, extension);
    var command = commandJars(project, extension);
    var setup = writeSetup(project, extension);
    for (String name : COMMANDS) {
      project
          .getTasks()
          .register(
              "mutastic" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1),
              MutasticTask.class,
              task -> {
                task.getCommand().set(name);
                task.getSetup().set(setup.flatMap(WriteSetup::getSetup));
                task.getCommandJars().from(command);
                task.from(extension);
                MutasticTask.configure(task, project);
              });
    }
  }

  /// `mutasticWriteSetup`, for the suite the command line's -Pmutastic.suite or the build file
  /// names: it decides which test task the setup reads.
  private static org.gradle.api.tasks.TaskProvider<WriteSetup> writeSetup(
      Project project, MutasticExtension extension) {
    var suite =
        project.getProviders().gradleProperty("mutastic.suite").orElse(extension.getSuite());
    return project
        .getTasks()
        .register(
            "mutasticWriteSetup",
            WriteSetup.class,
            task -> {
              task.getSuite().set(suite);
              WriteSetup.configure(task);
            });
  }

  /// The plugin's defaults, as conventions: what the build file sets replaces them.
  private static void defaults(Project project, MutasticExtension extension) {
    extension.getThreshold().convention(80);
    extension.getSuite().convention("test");
    extension.getReports().convention(List.of("json", "html"));
    extension.getConfirmCatches().convention(false);
    extension.getSuggestions().convention(true);
    extension.getToolVersion().convention(project.provider(MutasticPlugin::version));
    var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
    extension
        .getJavaLauncher()
        .convention(
            toolchains.launcherFor(
                spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(ENGINE_JDK))));
  }

  /// `mutastic`: the `mutastic` command's jars, of the extension's `toolVersion`, from the
  /// project's repositories. Built for 25, as the command runs on.
  private static Configuration commandJars(Project project, MutasticExtension extension) {
    var objects = project.getObjects();
    return project
        .getConfigurations()
        .create(
            "mutastic",
            configuration -> {
              configuration.setCanBeConsumed(false);
              configuration.setDescription("The mutastic command, which mutastic's tasks run");
              configuration.defaultDependencies(
                  dependencies ->
                      dependencies.add(
                          project
                              .getDependencies()
                              .create(
                                  "io.mutastic:mutastic-cli:" + extension.getToolVersion().get())));
              configuration.attributes(attributes -> jdk25Library(attributes, objects));
            });
  }

  /// A library's jars, for running on JDK 25: what the `mutastic` command is published as.
  private static void jdk25Library(AttributeContainer attributes, ObjectFactory objects) {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
    attributes.attribute(
        Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
    attributes.attribute(
        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
        objects.named(LibraryElements.class, LibraryElements.JAR));
    attributes.attribute(
        Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, ENGINE_JDK);
  }

  /// This plugin's version, from its jar.
  static String version() {
    var version = MutasticPlugin.class.getPackage().getImplementationVersion();
    if (version == null) {
      throw new IllegalStateException("mutastic's Gradle plugin has no version in its jar");
    }
    return version;
  }

  /// `./gradlew` as typed from `here` when the build has a wrapper, else `gradle`.
  static String gradle(File root, File here) {
    var wrapper = new File(root, "gradlew");
    if (!wrapper.isFile()) {
      return "gradle";
    }
    var relative = here.toPath().relativize(wrapper.toPath()).toString();
    return relative.startsWith("..") ? wrapper.getPath() : "./" + relative;
  }
}
