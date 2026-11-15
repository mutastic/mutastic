package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The summary's report files, as a person in a terminal reads and clicks them, from where they ran
/// mutastic. Run from `samples` on `testng`, the row once said `build/mutastic/mutation.html`,
/// which is there only from `testng`, and a terminal guessing a link from it opened
/// `/build/mutastic/mutation.html`. Each run is a process of its own, so it has a directory of its
/// own to run from.
class ReportPathsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void fromAParentTheReportsAreTypedFromThere() throws Exception {
    var parent = work.resolve("samples");
    Samples.copy(SAMPLE, parent.resolve("pricing"));

    var said = mutastic(parent, "run", "pricing", "--workers=1");

    var reports = reportsRow(said);
    assertThat(reports)
        .containsExactly(
            "pricing/build/mutastic/mutation.json", "pricing/build/mutastic/mutation.html");
    reports.forEach(file -> assertThat(parent.resolve(file)).isRegularFile());
  }

  @Test
  void withLinksOnEachReportLinksToTheFileItself() throws Exception {
    var parent = work.resolve("samples");
    var project = Samples.copy(SAMPLE, parent.resolve("pricing"));

    var said = mutastic(parent, "run", "pricing", "--workers=1", "--hyperlinks=always");

    var links = LINK.matcher(said).results().toList();
    var html =
        links.stream()
            .filter(link -> link.group("text").equals("pricing/build/mutastic/mutation.html"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(said));
    var uri = URI.create(html.group("uri"));
    assertThat(uri.getScheme()).isEqualTo("file");
    assertThat(Path.of(uri)).isAbsolute().isRegularFile();
    assertThat(Path.of(uri).toRealPath())
        .isEqualTo(project.resolve("build/mutastic/mutation.html").toRealPath());
  }

  @Test
  void fromInsideTheProjectTheReportsStayShort() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var said = mutastic(project, "run", "--workers=1");

    assertThat(reportsRow(said))
        .containsExactly("build/mutastic/mutation.json", "build/mutastic/mutation.html");
  }

  /// `text` linked to `uri`, as OSC 8 writes it.
  private static final Pattern LINK =
      Pattern.compile(
          "\u001b]8;;(?<uri>[^\u001b]+)\u001b\\\\(?<text>[^\u001b]*)\u001b]8;;\u001b\\\\");

  /// The files the `Reports` row names, as shown.
  private static List<String> reportsRow(String said) {
    var row =
        said.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("Reports  "))
            .findFirst()
            .orElseThrow(() -> new AssertionError(said));
    return List.of(row.substring("Reports  ".length()).split(" · ", -1));
  }

  /// What mutastic prints to stdout, run from `directory` as a person would.
  private static String mutastic(Path directory, String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName()));
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(said).isEqualTo(Main.PASSED);
    return said;
  }
}
