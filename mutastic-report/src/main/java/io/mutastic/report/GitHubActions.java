package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What a run tells GitHub Actions when it runs there, with no token or permission: a warning
/// annotation on the line of each of the first misses, ranked as the agent format ranks them, which
/// the pull request's diff shows; and a Markdown summary for the job's page.
public final class GitHubActions {
  /// How many misses get an annotation: as many as the agent format shows.
  static final int LIMIT = 10;

  private final Path root;
  private final SourceView sources;

  /// `root` is the project's directory, which the sources' paths are relative to.
  public GitHubActions(Path root, SourceView sources) {
    this.root = root;
    this.sources = sources;
  }

  /// `::warning file=…,line=…,title=…::…` for each of the first misses: a workflow command the
  /// runner turns into an annotation.
  public List<String> annotations(Report report) {
    return firstMisses(report).stream().map(this::annotation).toList();
  }

  private String annotation(MutantResult result) {
    var mutant = result.mutant();
    return "::warning file=%s,line=%d,title=%s::%s"
        .formatted(
            property(RepositoryPaths.fromRoot(root, sources.file(mutant))),
            sources.place(mutant).line(),
            property("mutastic: missed " + mutant.id()),
            data(sources.advice(result).headline()));
  }

  /// The job summary: the verdict and score, the counts, and the first misses with where they are.
  public String summary(Report report, int threshold) {
    return summary(report, threshold, Optional.empty());
  }

  /// The same, with `--fail-on-new` judging the run on its new misses, not its score.
  public String summary(Report report, int threshold, Optional<FailOnNew> judged) {
    var lines = new ArrayList<String>();
    var score = report.score();
    lines.add(
        "### mutastic: %s · score %s (%s)"
            .formatted(
                judged
                    .map(found -> found.verdict(report))
                    .orElseGet(() -> report.passes(threshold) ? "PASS" : "FAIL"),
                score.isPresent() ? "%.1f%%".formatted(score.getAsDouble()) : "none",
                judged.isPresent() ? "threshold not applied" : "threshold " + threshold));
    lines.add("");
    lines.add("| Caught | Missed | Not covered | Timed out |");
    lines.add("|---|---|---|---|");
    lines.add(
        "| %d | %d | %d | %d |"
            .formatted(
                report.count(Status.CAUGHT),
                report.count(Status.MISSED),
                report.count(Status.NOT_COVERED),
                report.count(Status.TIMED_OUT)));
    lines.addAll(missesTable(report));
    return String.join("\n", lines) + "\n";
  }

  /// The first misses as a table, and how many more there are; nothing when none was missed.
  private List<String> missesTable(Report report) {
    var first = firstMisses(report);
    if (first.isEmpty()) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    lines.add("");
    lines.add("**Missed, most worth a test first**");
    lines.add("");
    lines.add("| Mutant | Where | No test checks |");
    lines.add("|---|---|---|");
    first.forEach(result -> lines.add(row(result)));
    long more = report.count(Status.MISSED) - first.size();
    if (more > 0) {
      lines.add("");
      lines.add("and %d more missed. `mutastic explain <id>` shows any one.".formatted(more));
    }
    return lines;
  }

  /// A miss's row: its id, where it is, and what no test checks.
  private String row(MutantResult result) {
    var mutant = result.mutant();
    return "| `%s` | `%s:%d` | %s |"
        .formatted(
            mutant.id(),
            RepositoryPaths.fromRoot(root, sources.file(mutant)),
            sources.place(mutant).line(),
            sources.advice(result).headline().replace("|", "\\|"));
  }

  /// The first [#LIMIT] lines' lead misses, most worth a test first.
  private static List<MutantResult> firstMisses(Report report) {
    var missed =
        report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    return Finding.ranked(Finding.of(missed, false)).stream()
        .limit(LIMIT)
        .map(Finding::lead)
        .toList();
  }

  /// A workflow command's message, its `%`, carriage returns and newlines escaped.
  private static String data(String text) {
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
  }

  /// A workflow command's property: as [#data], and its `:` and `,` too.
  private static String property(String text) {
    return data(text).replace(":", "%3A").replace(",", "%2C");
  }
}
