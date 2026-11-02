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
/// commands, `mutasticRun`, `mutasticVerify`, `mutasticExplain` and `mutasticIgnore`. Their options
/// are the `mutastic.` properties (`-Pmutastic.threshold=60`), passed on as they are.
public abstract class MutasticPlugin implements Plugin<Project> {
  /// The JDK mutastic runs on, for the ClassFile API; a Gradle toolchain, so Gradle finds or
  /// fetches it. The tests still run on the JDK the `test` task uses.
  static final int ENGINE_JDK = 25;

  private static final List<String> COMMANDS = List.of("run", "verify", "explain", "ignore");

  @Override
  public void apply(Project project) {
    project.getPlugins().withType(JavaPlugin.class, java -> configure(project));
  }

  private static void configure(Project project) {
    var command = commandJars(project);
    var setup =
        project.getTasks().register("mutasticWriteSetup", WriteSetup.class, WriteSetup::configure);
    var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
    var engineJava =
        toolchains.launcherFor(
            spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(ENGINE_JDK)));
    for (String name : COMMANDS) {
      project
          .getTasks()
          .register(
              "mutastic" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1),
              MutasticTask.class,
              task -> {
                task.getCommand().set(name);
                task.getSetup().set(setup.flatMap(WriteSetup::getSetup));
                task.getJava().set(engineJava);
                task.getCommandJars().from(command);
                MutasticTask.configure(task, project);
              });
    }
  }

  /// `mutastic`: the `mutastic` command's jars, of this plugin's version, from the project's
  /// repositories. Built for 25, as the command runs on.
  private static Configuration commandJars(Project project) {
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
                              .create("io.mutastic:mutastic-cli:" + version())));
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
