package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// `mutastic explain`: the mutant's card as a run would show it, then what it is: the bytecode
/// change opcode by opcode and how the jump relates to the source, the operator, the id, the site,
/// and how the last full run went.
class ExplainTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String ID = "m855c8e3c";

  @TempDir Path work;

  @Test
  void theCardThenWhatTheMutantIs() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var before = Samples.mutastic("explain", ID, project.toString());
    Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");
    var after = Samples.mutastic("explain", ID, project.toString());

    assertThat(before.exit()).isEqualTo(Main.PASSED);
    assertThat(before.out())
        .contains("[✗ MISSED] no test checks discount() at total == 100")
        .contains("void discountAtTheTotalEdge()")
        .contains("About this mutant")
        .contains("= bytecode  IF_ICMPLT → IF_ICMPLE")
        .contains("IF_ICMPLT: jump if the first int is less than the second")
        .contains(
            "javac compiles `total >= 100` to IF_ICMPLT, a jump past the body when `total < 100`")
        .contains("= id        " + ID + ": `m` and the first 8 hex digits of its fingerprint")
        .contains("= last run  none yet");
    assertThat(after.out()).contains("= last run  missed: every covering test still passed");
  }

  /// `next` runs every mutant the first time, so that's the last run too.
  @Test
  void nextCountsAsARun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    Samples.mutastic("next", project.toString());
    var explained = Samples.mutastic("explain", ID, project.toString());

    assertThat(explained.out()).contains("= last run  missed: every covering test still passed");
  }

  /// `--format=jsonl`: the events a run's JSON Lines has for the mutant, then `mutant-explained`,
  /// whose fields are the text's rows in the same words. `Samples` checks each event against the
  /// published schema.
  @Test
  void asEventsTheTextsRowsAreFields() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    var text = Samples.mutastic("explain", ID, project.toString());
    var events = Samples.mutastic("explain", ID, project.toString(), "--format=jsonl");

    assertThat(events.exit()).as(events.err()).isEqualTo(Main.PASSED);
    var read = events.out().lines().map(JsonMapper.shared()::readTree).toList();
    assertThat(read)
        .extracting(event -> event.path("reason").asString())
        .containsExactly("run-started", "mutant-finished", "run-finished", "mutant-explained");
    assertThat(read.get(1).path("id").asString()).isEqualTo(ID);
    var explained = read.getLast();
    var about = flattened(text.out().substring(text.out().indexOf("About this mutant")));
    var bytecode = explained.path("bytecode");
    assertThat(explained.path("id").asString()).isEqualTo(ID);
    assertThat(about)
        .contains(
            "bytecode %s → %s"
                .formatted(
                    bytecode.path("original").asString(), bytecode.path("replacement").asString()))
        .contains(proseOf(explained))
        .contains("site " + explained.path("site").path("description").asString())
        .contains("last run " + explained.path("lastRun").path("description").asString());
    assertThat(bytecode.path("meanings")).hasSize(2);
    assertThat(explained.path("site").path("ordinal").asInt()).isEqualTo(1);
    assertThat(explained.path("site").path("method").asString()).isEqualTo("discount(I)I");
    assertThat(explained.path("lastRun").path("status").asString()).isEqualTo("Survived");
  }

  /// Before any run there's no last run to tell of, so no field for it.
  @Test
  void asEventsWithNoLastRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var events = Samples.mutastic("explain", ID, project.toString(), "--format=jsonl");

    var explained = JsonMapper.shared().readTree(events.out().lines().toList().getLast());
    assertThat(explained.path("reason").asString()).isEqualTo("mutant-explained");
    assertThat(explained.has("lastRun")).isFalse();
  }

  /// A format with no explanation to give is refused before any test runs, naming those it takes.
  @Test
  void anotherFormatIsRefused() {
    var refused = Samples.mutastic("explain", ID, SAMPLE.toString(), "--format=agent");

    assertThat(refused.exit()).isEqualTo(Main.USAGE);
    assertThat(refused.err()).contains("explain takes --format=full or --format=jsonl");
  }

  /// Each prose field, flattened as the text is: the meanings, the jump's note, the operator, the
  /// id's rows.
  private static String[] proseOf(JsonNode explained) {
    var prose = new java.util.ArrayList<String>();
    explained.path("bytecode").path("meanings").forEach(meaning -> prose.add(meaning.asString()));
    prose.add(explained.path("bytecode").path("note").asString());
    var operator = explained.path("operator");
    prose.add(
        "operator %s: %s"
            .formatted(operator.path("id").asString(), operator.path("description").asString()));
    prose.add(
        "id %s %s"
            .formatted(
                explained.path("idDescription").asString(), explained.path("idNote").asString()));
    assertThat(explained.path("idDescription").asString())
        .contains(explained.path("fingerprint").asString());
    return prose.stream().map(ExplainTest::flattened).toArray(String[]::new);
  }

  /// The words of `text`, unwrapped: each run of spaces and line breaks as one space, and without
  /// the `=` that starts each row.
  private static String flattened(String text) {
    return String.join(
        " ",
        List.of(text.strip().split("\\s+")).stream().filter(word -> !word.equals("=")).toList());
  }
}
