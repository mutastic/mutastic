package io.mutastic.report;

import java.io.PrintStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/// The events a run writes as it goes, among the others of `--format=jsonl`: each phase as it
/// starts and ends, and how far the one under way is, on the heartbeat's cadence. `PROGRESS.md`
/// says what each holds, and the events schema describes them.
public final class LiveEvents {
  private final PrintStream out;

  public LiveEvents(PrintStream out) {
    this.out = out;
  }

  /// `phase` (`reading`, `building`, `coverage`, `mutants` or `checking`) has started.
  public void phaseStarted(String phase) {
    emit("phase-started", Map.of("phase", phase));
  }

  /// `phase` has ended, having taken `took`.
  public void phaseFinished(String phase, Duration took) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("phase", phase);
    fields.put("durationMs", took.toMillis());
    emit("phase-finished", fields);
  }

  /// How far `phase` is: `fields`, in order, from `elapsedMs` on.
  public void progress(String phase, Map<String, Object> fields) {
    var all = new LinkedHashMap<String, Object>();
    all.put("phase", phase);
    all.putAll(fields);
    emit("progress", all);
  }

  /// One line, whole, so another thread's event never lands inside it.
  private void emit(String reason, Map<String, Object> fields) {
    var event = new LinkedHashMap<String, Object>();
    event.put("schema", JsonEvents.SCHEMA);
    event.put("edition", JsonEvents.EDITION);
    event.put("reason", reason);
    event.putAll(fields);
    out.print(Json.write(event) + "\n");
    out.flush();
  }
}
