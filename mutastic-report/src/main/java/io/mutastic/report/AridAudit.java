package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.util.List;

/// What an audit of the arid filters found (`MUTASTIC_ARID=audit`): the mutants they skip, run
/// anyway. A catch by an assertion means a test checks what the arid code returns, so a filter may
/// be hiding a contract; a catch by a crash (a `null` getter breaking the code that uses it) only
/// means the code is used, which says nothing either way.
///
/// @param byAssertion the catches where a test's own check failed
/// @param caughtIn the methods of the catches by an assertion, `Board.toString()`, in order found
record AridAudit(
    int ran, long caught, long byAssertion, long missed, long notCovered, List<String> caughtIn) {
  static AridAudit of(Report report) {
    var audited = report.audited();
    return new AridAudit(
        audited.size(),
        audited.stream().filter(result -> result.status().detected()).count(),
        audited.stream().filter(AridAudit::byAssertion).count(),
        count(audited, Status.MISSED),
        count(audited, Status.NOT_COVERED),
        audited.stream().filter(AridAudit::byAssertion).map(AridAudit::method).distinct().toList());
  }

  boolean happened() {
    return ran > 0;
  }

  /// `12 ran anyway: 3 caught (1 by an assertion), 8 missed, 1 not covered; checked in
  /// Board.toString()`.
  String summary() {
    var counts =
        "%d ran anyway: %d caught (%d by an assertion), %d missed, %d not covered"
            .formatted(ran, caught, byAssertion, missed, notCovered);
    if (caughtIn.isEmpty()) {
      return counts;
    }
    var shown = caughtIn.subList(0, Math.min(3, caughtIn.size()));
    return counts
        + "; checked in "
        + String.join(", ", shown)
        + (caughtIn.size() > shown.size()
            ? " and %d more".formatted(caughtIn.size() - shown.size())
            : "");
  }

  private static boolean byAssertion(MutantResult result) {
    return result.status().detected()
        && result.failureOrigin().filter("assertion"::equals).isPresent();
  }

  private static long count(List<MutantResult> results, Status status) {
    return results.stream().filter(result -> result.status() == status).count();
  }

  private static String method(MutantResult result) {
    var mutant = result.mutant();
    var simple = mutant.className().substring(mutant.className().lastIndexOf('/') + 1);
    return simple + "." + mutant.methodName() + "()";
  }
}
