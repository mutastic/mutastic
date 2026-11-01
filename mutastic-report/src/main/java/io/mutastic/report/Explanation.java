package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.Status;
import java.util.List;
import java.util.Optional;

/// Everything known of one mutant, without running a test (`mutastic explain`): where it is, the
/// line before and after, what the change is and the gap a miss would show, what its operator does,
/// how the last full run went, and why it's skipped if it is.
public final class Explanation {
  private final SourceView sources;

  public Explanation(SourceView sources) {
    this.sources = sources;
  }

  /// How the last full run went for it: its status, and the test that caught it if one did.
  public record LastRun(Status status, Optional<String> caughtBy) {}

  public String render(Mutant mutant, Optional<LastRun> lastRun) {
    var advice = sources.advice(mutant);
    var out = new StringBuilder();
    out.append(
        "%s · %s · %s:%d in %s%n"
            .formatted(
                mutant.id(),
                mutant.operator().id(),
                sources.file(mutant),
                mutant.line(),
                Advice.method(mutant)));
    appendChange(out, mutant, advice);
    out.append("   what: %s%n".formatted(mutant.operator().explanation()));
    out.append(
        "   last run: %s%n".formatted(lastRun.map(Explanation::described).orElse("none yet")));
    mutant.skipped().ifPresent(reason -> out.append("   skipped: %s%n".formatted(reason)));
    appendCommands(out, mutant);
    return out.toString();
  }

  private void appendChange(StringBuilder out, Mutant mutant, Advice advice) {
    var line = sources.line(mutant);
    var changed = sources.mutatedLine(mutant, advice);
    if (line.isPresent() && changed.isPresent()) {
      out.append("   - %s%n   + %s%n".formatted(line.get().strip(), changed.get().strip()));
    }
    out.append("   change: %s%n".formatted(advice.change()));
    out.append("   if missed: %s%n".formatted(advice.headline()));
  }

  private void appendCommands(StringBuilder out, Mutant mutant) {
    for (MutantCommand command :
        List.of(MutantCommand.DONE_WHEN, MutantCommand.REPRODUCE, MutantCommand.IGNORE)) {
      out.append(
          "   %s: %s%n".formatted(command.label(), command.text(mutant.id(), sources.target())));
    }
  }

  private static String described(LastRun run) {
    return switch (run.status()) {
      case CAUGHT ->
          "caught" + run.caughtBy().map(test -> " by " + AgentText.shortName(test)).orElse("");
      case MISSED -> "missed: every covering test still passed";
      case TIMED_OUT -> "caught: its tests ran out of time";
      case NOT_COVERED -> "not covered: no test reached its line";
      case SKIPPED -> "skipped";
    };
  }
}
