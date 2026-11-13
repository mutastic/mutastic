package io.mutastic.cli;

import io.mutastic.api.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// A `progress` event's fields, as `PROGRESS.md` lists them: a pure function of the phase's
/// snapshot, as the frames are. What the phase doesn't know is left out.
final class ProgressFields {
  private ProgressFields() {}

  /// Always `elapsedMs`; `done` and `total` where there's a count; and for the mutants, `weighted`
  /// to three decimals, `leftMs` once there's an estimate, the four fates and the busy workers.
  static Map<String, Object> of(Snapshot snapshot) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("elapsedMs", snapshot.elapsed().toMillis());
    if (snapshot.phase() == Phase.MUTANTS || counted(snapshot)) {
      fields.put("done", snapshot.done());
      fields.put("total", snapshot.total());
    }
    if (snapshot.phase() == Phase.MUTANTS) {
      putMutants(snapshot, fields);
    }
    return fields;
  }

  /// The coverage pass and the checking count once they know how many there are.
  private static boolean counted(Snapshot snapshot) {
    var phase = snapshot.phase();
    return (phase == Phase.COVERAGE || phase == Phase.CHECKING) && snapshot.total() > 0;
  }

  private static void putMutants(Snapshot snapshot, Map<String, Object> fields) {
    var estimate = snapshot.estimate();
    estimate
        .weighted()
        .ifPresent(weighted -> fields.put("weighted", Math.round(weighted * 1000) / 1000.0));
    estimate.left().ifPresent(left -> fields.put("leftMs", left.toMillis()));
    fields.put("caught", snapshot.count(Status.CAUGHT));
    fields.put("missed", snapshot.count(Status.MISSED));
    fields.put("notCovered", snapshot.count(Status.NOT_COVERED));
    fields.put("timedOut", snapshot.count(Status.TIMED_OUT));
    fields.put("running", snapshot.workers().stream().filter(Optional::isPresent).count());
  }
}
