package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The Maven plugin, run by real Maven on `samples/maven-modules`, resolved from a repository in
/// the build as it would be from Central. `core`'s boundary is checked only by `app`'s test, so
/// it's missed, and the command the run offers to prove a fix is a goal that runs as it's copied.
class MavenPluginTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "maven-modules");
  private static final Path REPOSITORY = Path.of(System.getProperty("mutastic.samplesRepository"));
  private static final String PLUGIN =
      "io.mutastic:mutastic-maven-plugin:" + System.getProperty("mutastic.version");
  private static final Pattern DONE_WHEN =
      Pattern.compile("\"doneWhen\":\"(?<command>mvn [^\"]*) passes\"");

  @TempDir Path work;

  @Test
  void eachModuleIsMutatedAndTheCommandsOfferedAreGoals() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("maven-modules"));

    var run = mvn(project, PLUGIN + ":run", "-Dmutastic.format=jsonl", "-Dmutastic.threshold=0");

    assertThat(run.exit()).as(run.out()).isZero();
    // The root is a pom, with nothing to mutate; core and app each run on their own.
    assertThat(run.out())
        .contains("\"file\":\"core/src/main/java/com/example/core/Fees.java\"")
        .contains("\"file\":\"app/src/main/java/com/example/app/Checkout.java\"");
    var doneWhen = DONE_WHEN.matcher(run.out());
    assertThat(doneWhen.find()).as(run.out()).isTrue();
    // Not declared in the sample's build, so named by its coordinates, for core alone.
    var command = doneWhen.group("command");
    assertThat(command)
        .startsWith("mvn " + PLUGIN + ":verify -Dmutastic.mutant=m")
        .endsWith(" -pl core");

    var verify = mvn(project, command.substring("mvn ".length()).split(" ", -1));

    assertThat(verify.out()).as(verify.out()).contains("is still missed");
    assertThat(verify.exit()).isEqualTo(1);
  }

  /// `mvn` in `project`, from the build's repository; snapshots checked each time (`-U`), so the
  /// plugin is the one just built.
  private Samples.Result mvn(Path project, String... arguments)
      throws IOException, InterruptedException {
    var settings = work.resolve("settings.xml");
    Files.writeString(settings, settings(REPOSITORY.toUri().toString()));
    var command = new ArrayList<>(List.of("mvn", "-B", "-U", "-s", settings.toString()));
    command.add("-Dmaven.repo.local=" + REPOSITORY.resolveSibling("samples-maven-local"));
    command.addAll(List.of(arguments));
    var log = work.resolve("mvn.log");
    int exit =
        new ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
            .waitFor();
    return new Samples.Result(exit, Files.readString(log, StandardCharsets.UTF_8), "");
  }

  /// Settings with the build's repository beside Central, for plugins and dependencies both.
  private static String settings(String repository) {
    var entry =
        "<id>samples</id><url>%s</url><snapshots><enabled>true</enabled></snapshots>"
            .formatted(repository);
    return """
        <settings>
          <profiles>
            <profile>
              <id>samples</id>
              <repositories><repository>%s</repository></repositories>
              <pluginRepositories><pluginRepository>%s</pluginRepository></pluginRepositories>
            </profile>
          </profiles>
          <activeProfiles><activeProfile>samples</activeProfile></activeProfiles>
        </settings>
        """
        .formatted(entry, entry);
  }
}
