package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/// A reader takes a field or an event it doesn't know, as the published schema does; the tests'
/// strict reading refuses both, so mutastic can't write one the schema doesn't describe.
class SchemasTest {
  private static final String STARTED =
      "{\"schema\":\"mutastic.events/v1\",\"edition\":\"java\",\"reason\":\"run-started\","
          + "\"project\":\"pricing\",\"rule\":\"r\",\"decline\":\"d\"%s}";

  @Test
  void aFieldNobodyDescribedIsTakenByReadersAndRefusedByTheTests() {
    var typo = STARTED.formatted(",\"projekt\":\"pricing\"");

    assertThat(Schemas.problems(Schemas.schema(Schemas.read(Schemas.EVENTS)), typo)).isEmpty();
    assertThatThrownBy(() -> Schemas.checkEvents(typo)).hasMessageContaining("projekt");
    Schemas.checkEvents(STARTED.formatted(""));
  }

  @Test
  void anEventNobodyDescribedIsTakenByReadersAndRefusedByTheTests() {
    var unknown =
        "{\"schema\":\"mutastic.events/v1\",\"edition\":\"java\",\"reason\":\"coverage-finished\"}";

    assertThat(Schemas.problems(Schemas.schema(Schemas.read(Schemas.EVENTS)), unknown)).isEmpty();
    assertThatThrownBy(() -> Schemas.checkEvents(unknown)).hasMessageContaining("reason");
  }

  @Test
  void aMissedMutantWithoutItsCommandsIsRefused() {
    var events = Schemas.schema(Schemas.read(Schemas.EVENTS));
    var missed =
        "{\"schema\":\"mutastic.events/v1\",\"edition\":\"java\",\"reason\":\"mutant-finished\","
            + "\"id\":\"m855c8e3c\","
            + "\"fingerprint\":\"855c8e3ca8a39847\",\"status\":\"Survived\","
            + "\"operator\":\"mutastic/boundary\",\"location\":{\"file\":\"P.java\",\"line\":17,"
            + "\"class\":\"P\",\"method\":\"d(I)I\"},\"mutation\":{\"description\":\"x\","
            + "\"applied\":{\"level\":\"bytecode\",\"text\":\"y\"}},\"coveredBy\":[],\"testsRun\":0,"
            + "\"durationMs\":1}";

    assertThat(Schemas.problems(events, missed)).anyMatch(problem -> problem.contains("doneWhen"));
    assertThat(Schemas.problems(events, missed.replace("Survived", "NoCoverage"))).isEmpty();
  }

  /// `applied` is the change as each edition's engine makes it: the Java edition's to the
  /// instruction, and the Rust edition's to the source. Every mutant has it.
  @Test
  void eachEditionSaysAtWhatLevelItAppliedTheChange() {
    var finished =
        "{\"schema\":\"mutastic.events/v1\",\"reason\":\"mutant-finished\",%s"
            + "\"id\":\"m855c8e3c\",\"fingerprint\":\"855c8e3ca8a39847\",\"status\":\"Killed\","
            + "\"operator\":\"mutastic/boundary\",\"location\":{\"file\":\"P\",\"line\":17,"
            + "\"class\":\"P\",\"method\":\"d\"},\"mutation\":{\"description\":\"x\",%s},"
            + "\"coveredBy\":[],\"testsRun\":0,\"durationMs\":1}";
    var java = "\"applied\":{\"level\":\"bytecode\",\"text\":\"IF_ICMPLT -> IF_ICMPLE\"}";
    var rust = "\"applied\":{\"level\":\"source\",\"text\":\"x < 0 -> true\"}";

    Schemas.checkEvents(finished.formatted("\"edition\":\"java\",", java));
    assertThatThrownBy(
            () ->
                Schemas.checkEvents(
                    finished.formatted("\"edition\":\"java\",", "\"original\":\">=\"")))
        .hasMessageContaining("applied");
    Schemas.checkEvents(finished.formatted("\"edition\":\"rust\",", rust));
    // A level a later engine adds, such as `llvm-ir`, is one a reader takes.
    Schemas.checkEvents(
        finished.formatted("\"edition\":\"rust\",", rust.replace("source", "llvm-ir")));
    assertThatThrownBy(
            () ->
                Schemas.checkEvents(
                    finished.formatted(
                        "\"edition\":\"rust\",", "\"applied\":{\"level\":\"source\"}")))
        .hasMessageContaining("text");
  }

  /// Every event says which edition wrote it, and a missed mutant has its commands in either.
  @Test
  void everyEventNamesItsEditionAndAMissHasItsCommands() {
    var missed =
        "{\"schema\":\"mutastic.events/v1\",\"reason\":\"mutant-finished\",%s"
            + "\"id\":\"m855c8e3c\",\"fingerprint\":\"855c8e3ca8a39847\",\"status\":\"Survived\","
            + "\"operator\":\"mutastic/boundary\",\"location\":{\"file\":\"src/lib.rs\","
            + "\"line\":17,\"column\":9,\"class\":\"pricing\",\"method\":\"discount\"},"
            + "\"mutation\":{\"description\":\"x\",\"applied\":{\"level\":\"source\",\"text\":\"y\"}},"
            + "\"coveredBy\":[],\"testsRun\":0,\"durationMs\":1,\"doneWhen\":\"d\",\"reproduce\":\"r\","
            + "\"rendered\":\"e\"%s}";
    var commands = ",\"ignore\":\"i\",\"propose\":\"p\"";

    Schemas.checkEvents(missed.formatted("\"edition\":\"rust\",", commands));
    assertThatThrownBy(() -> Schemas.checkEvents(missed.formatted("", commands)))
        .hasMessageContaining("edition");
    assertThatThrownBy(() -> Schemas.checkEvents(missed.formatted("\"edition\":\"rust\",", "")))
        .hasMessageContaining("ignore");
  }
}
