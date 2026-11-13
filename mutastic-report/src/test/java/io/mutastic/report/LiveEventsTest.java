package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/// The live events, as `PROGRESS.md` shows them, each one the events schema describes in full.
class LiveEventsTest {
  @Test
  void eachPhaseStartsEndsAndSaysHowFarItIs() {
    var bytes = new ByteArrayOutputStream();
    var events = new LiveEvents(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    var mutants = new LinkedHashMap<String, Object>();
    mutants.put("elapsedMs", 1_450_000L);
    mutants.put("done", 2871);
    mutants.put("total", 5525);
    mutants.put("weighted", 0.52);
    mutants.put("leftMs", 1_260_000L);
    mutants.put("caught", 2610);
    mutants.put("missed", 41);
    mutants.put("notCovered", 214);
    mutants.put("timedOut", 6);
    mutants.put("running", 8);

    events.phaseStarted("building");
    events.progress("mutants", mutants);
    events.phaseFinished("building", Duration.ofSeconds(581));

    var written = bytes.toString(StandardCharsets.UTF_8);
    assertThat(written)
        .isEqualTo(
            """
            {"schema":"mutastic.events/v1","edition":"java","reason":"phase-started","phase":"building"}
            {"schema":"mutastic.events/v1","edition":"java","reason":"progress","phase":"mutants","elapsedMs":1450000,"done":2871,"total":5525,"weighted":0.52,"leftMs":1260000,"caught":2610,"missed":41,"notCovered":214,"timedOut":6,"running":8}
            {"schema":"mutastic.events/v1","edition":"java","reason":"phase-finished","phase":"building","durationMs":581000}
            """);
    Schemas.checkEvents(written);
  }
}
