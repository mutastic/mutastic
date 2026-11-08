package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/// A reader takes a field or an event it doesn't know, as the published schema does; the tests'
/// strict reading refuses both, so mutastic can't write one the schema doesn't describe.
class SchemasTest {
  private static final String STARTED =
      "{\"schema\":\"mutastic.events/v1\",\"reason\":\"run-started\",\"project\":\"pricing\","
          + "\"rules\":\"r\",\"decline\":\"d\"%s}";

  @Test
  void aFieldNobodyDescribedIsTakenByReadersAndRefusedByTheTests() {
    var typo = STARTED.formatted(",\"projekt\":\"pricing\"");

    assertThat(Schemas.problems(Schemas.schema(Schemas.read(Schemas.EVENTS)), typo)).isEmpty();
    assertThatThrownBy(() -> Schemas.checkEvents(typo)).hasMessageContaining("projekt");
    Schemas.checkEvents(STARTED.formatted(""));
  }

  @Test
  void anEventNobodyDescribedIsTakenByReadersAndRefusedByTheTests() {
    var unknown = "{\"schema\":\"mutastic.events/v1\",\"reason\":\"coverage-finished\"}";

    assertThat(Schemas.problems(Schemas.schema(Schemas.read(Schemas.EVENTS)), unknown)).isEmpty();
    assertThatThrownBy(() -> Schemas.checkEvents(unknown)).hasMessageContaining("reason");
  }

  @Test
  void aMissedMutantWithoutItsCommandsIsRefused() {
    var events = Schemas.schema(Schemas.read(Schemas.EVENTS));
    var missed =
        "{\"schema\":\"mutastic.events/v1\",\"reason\":\"mutant-finished\",\"id\":\"m855c8e3c\","
            + "\"fingerprint\":\"855c8e3ca8a39847\",\"status\":\"Survived\","
            + "\"operator\":\"mutastic/boundary\",\"location\":{\"file\":\"P.java\",\"line\":17,"
            + "\"class\":\"P\",\"method\":\"d(I)I\"},\"mutation\":{\"description\":\"x\","
            + "\"bytecode\":\"y\"},\"coveredBy\":[],\"testsRun\":0,\"durationMs\":1}";

    assertThat(Schemas.problems(events, missed)).anyMatch(problem -> problem.contains("doneWhen"));
    assertThat(Schemas.problems(events, missed.replace("Survived", "NoCoverage"))).isEmpty();
  }

  /// What only the Java edition writes (`jdk`, a mutation's `bytecode`, a missed mutant's `ignore`
  /// and `propose`) is required of an event with no `edition`, and not of the Rust edition's.
  @Test
  void whatOnlyJavaWritesIsRequiredOnlyOfJava() {
    var missed =
        "{\"schema\":\"mutastic.events/v1\",\"reason\":\"mutant-finished\",%s"
            + "\"id\":\"m855c8e3c\",\"fingerprint\":\"855c8e3ca8a39847\",\"status\":\"Survived\","
            + "\"operator\":\"mutastic/boundary\",\"location\":{\"file\":\"src/lib.rs\","
            + "\"line\":17,\"column\":9,\"class\":\"pricing\",\"method\":\"discount\"},"
            + "\"mutation\":{\"description\":\"x\"},\"coveredBy\":[],\"testsRun\":0,"
            + "\"durationMs\":1,\"doneWhen\":\"d\",\"reproduce\":\"r\",\"rendered\":\"e\"}";

    Schemas.checkEvents(missed.formatted("\"edition\":\"rust\","));
    assertThatThrownBy(() -> Schemas.checkEvents(missed.formatted("")))
        .hasMessageContaining("bytecode");
    assertThatThrownBy(() -> Schemas.checkEvents(missed.formatted("\"edition\":\"java\",")))
        .hasMessageContaining("ignore");
  }
}
