package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.util.LinkedHashMap;
import java.util.List;

/// Ignores that repeat: several in one method say the same thing again and again, which one `skip:`
/// rule in `mutastic.yaml` says once, and goes on saying as the code changes. Google found turning
/// repeated dismissals into rules did more than dismissing each mutant.
final class RepeatedIgnores {
  /// Ignores in one method before a rule is suggested.
  static final int AT_LEAST = 3;

  /// `com.example.Pricing#discount`, as a rule names it, and how many of its mutants are ignored.
  record Repeated(String method, long count) {}

  private RepeatedIgnores() {}

  static List<Repeated> in(Report report) {
    var byMethod = new LinkedHashMap<String, Long>();
    for (MutantResult result : report.results()) {
      if (result.status() == Status.SKIPPED
          && result.mutant().skipped().filter(reason -> reason.endsWith("(ignored)")).isPresent()) {
        var mutant = result.mutant();
        byMethod.merge(mutant.binaryName() + "#" + mutant.methodName(), 1L, Long::sum);
      }
    }
    return byMethod.entrySet().stream()
        .filter(entry -> entry.getValue() >= AT_LEAST)
        .map(entry -> new Repeated(entry.getKey(), entry.getValue()))
        .toList();
  }

  /// `3 ignores in com.example.Pricing#discount: one rule says it once, `skip:
  /// com.example.Pricing#discount` in mutastic.yaml`
  static String text(Repeated repeated) {
    return "%d ignores in %s: one rule says it once, `skip: %s` in mutastic.yaml"
        .formatted(repeated.count(), repeated.method(), repeated.method());
  }
}
