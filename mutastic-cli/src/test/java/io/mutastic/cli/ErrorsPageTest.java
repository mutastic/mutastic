package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/// The errors page (reference/errors) has an entry for every code a failure has, and no other; each
/// output it shows is what mutastic prints, on a sample, with that code in its `run-failed` event,
/// so neither the page nor an agent reading the event can drift from the binary.
///
/// To write its outputs: `./gradlew :mutastic-cli:exportDocsOutput`.
class ErrorsPageTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path PAGE =
      Path.of(System.getProperty("mutastic.docs"), "reference/errors.mdx");

  /// A terminal, in colour; and the events on stdout, so each run's code can be read.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "jsonl");

  /// An entry's heading: `` ## `unknown-option` ``.
  private static final Pattern ENTRY = Pattern.compile("(?m)^### `(?<code>[a-z][a-z0-9-]*)`$");

  @TempDir Path work;

  @Test
  void thePageHasAnEntryForEveryCodeAndNoOther() throws Exception {
    var entries =
        ENTRY.matcher(Files.readString(PAGE)).results().map(found -> found.group("code")).toList();

    assertThat(entries).containsExactlyInAnyOrderElementsOf(Failure.CODES);
  }

  @Test
  void mistakesInTheCommandLine() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));

    shown("unknown-option", project, "run", project.toString(), "--formt=agent");
    shown("option-value", project, "run", project.toString(), "--format", "agnt");
    shown("unknown-command", project, "verfiy", "m855c8e3c", project.toString());
    shown("no-directory", project, "run", project.resolve("nosuch").toString());
    shown("unknown-id", project, "verify", "m855c83ec", project.toString());
  }

  @Test
  void mistakesInAFile() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    Files.writeString(
        project.resolve(Configuration.FILE), "operators:\n  - defaults\n  - -strem\n");

    shown("config", project, "run", project.toString());
  }

  @Test
  void theBuildAndItsTests() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var source = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    var original = Files.readString(source);
    Files.writeString(source, original.replace("total / 10;", "total / 10"));
    shown("build-failed", project, "run", project.toString());
    Files.writeString(source, original);

    var test = project.resolve("src/test/java/com/example/pricing/HaltsTest.java");
    Files.writeString(
        test,
        """
        package com.example.pricing;

        import org.junit.jupiter.api.Test;

        class HaltsTest {
          @Test
          void halts() {
            System.exit(3);
          }
        }
        """);
    shown("tests-stopped", project, "run", project.toString());
  }

  /// The event an agent reads for one of them, whole.
  @Test
  void theEvent() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));

    var run = Samples.mutastic(TERMINAL, "run", project.toString(), "--formt=jsonl");

    var whole = "\"path\":\"" + project.toRealPath() + "\"";
    var event = run.out().strip().lines().toList().getLast();
    assertThat(event).contains("\"reason\":\"run-failed\"", "\"applicability\":\"maybe\"", whole);
    // The page reads it as run from a checkout in your home directory.
    event = event.replace(whole, "\"path\":\"/home/you/mutastic/samples/pricing\"");
    var mapper = JsonMapper.builder().build();
    var laidOut =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(event));
    DocsOutputTest.shown(
        "errors-event.json", project, new Samples.Result(run.exit(), laidOut + "\n", ""));
  }

  /// What `args` prints from its message on, kept as the page's `errors-<code>.ansi`, after
  /// checking its event names `code`.
  private static void shown(String code, Path project, String... args) throws Exception {
    var run = Samples.mutastic(TERMINAL, args);
    var event = run.out().strip().lines().toList().getLast();

    assertThat(event)
        .as(run.err())
        .contains("\"reason\":\"run-failed\"", "\"code\":\"" + code + "\"");
    assertThat(Files.readString(PAGE)).contains("errors-" + code + ".ansi");
    DocsOutputTest.shown("errors-" + code + ".ansi", project, DocsOutputTest.fromItsMessage(run));
  }
}
