package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.api.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What `explain` teaches after a mutant's card: the change in the bytecode, opcode by opcode, and
/// how a jump relates to the source; what the operator does; how the id is made; which site it is;
/// and how the last full run went.
public final class Explanation {
  /// Under the text after a row's label: ` = bytecode `.
  private static final String INDENT = " ".repeat(16);

  /// At most this many columns, indent included, broken between words.
  private static final int WIDTH = 100;

  private final SourceView sources;
  private final Style style;

  public Explanation(SourceView sources, Style style) {
    this.sources = sources;
    this.style = style;
  }

  /// How the last full run went for it: its status, and the test that caught it if one did.
  public record LastRun(Status status, Optional<String> caughtBy) {}

  public String render(Mutant mutant, Optional<LastRun> lastRun) {
    var rows = new ArrayList<String>();
    rows.add(heading());
    addRow(rows, "bytecode", bytecode(mutant));
    addRow(
        rows, "operator", List.of(mutant.operator().id() + ": " + mutant.operator().explanation()));
    addRow(rows, "id", id(mutant));
    addRow(rows, "site", List.of(site(mutant)));
    addRow(
        rows,
        "last run",
        List.of(lastRun.map(Explanation::described).orElse("none yet: a full run records one")));
    return String.join("\n", rows) + "\n";
  }

  private String heading() {
    var title = " About this mutant ";
    return "\n" + style.bold(title) + style.dim("─".repeat(Math.max(4, 66 - title.length())));
  }

  /// `IF_ICMPLT → IF_ICMPLE`, then what each does, then how the jump relates to the source.
  private List<String> bytecode(Mutant mutant) {
    var lines = new ArrayList<String>();
    lines.add(style.bold(mutant.original() + " → " + mutant.replacement()));
    Opcodes.describe(mutant.original())
        .ifPresent(words -> lines.add(mutant.original() + ": " + words));
    Opcodes.describe(mutant.replacement())
        .ifPresent(words -> lines.add(mutant.replacement() + ": " + words));
    jump(mutant).ifPresent(lines::add);
    return lines;
  }

  /// A conditional jump usually tests the opposite of the source: javac jumps past an `if`'s body
  /// when its condition is false. Said in the mutant's own terms when its operands are known.
  private Optional<String> jump(Mutant mutant) {
    var operator = mutant.operator();
    if (operator != Operator.BOUNDARY && operator != Operator.NEGATE_CONDITIONAL) {
      return Optional.empty();
    }
    if (sources.asWritten(mutant)) {
      return Optional.of(
          "here the jump is taken when the condition holds, as the first test of an `||` is, so"
              + " it reads as the source does");
    }
    return Optional.of(
        concrete(mutant)
            .orElse(
                "javac compiles an `if` to a jump past its body when the condition is false, so"
                    + " the jump tests the opposite of the source"));
  }

  /// `javac compiles total >= 100 to IF_ICMPLT, a jump past the body when total < 100 ...`.
  private Optional<String> concrete(Mutant mutant) {
    var operands = mutant.operands();
    var advice = sources.advice(mutant);
    var jumps = Opcodes.symbol(mutant.original());
    var mutatedJumps = Opcodes.symbol(mutant.replacement());
    if (operands.size() != 2
        || !known(List.of(advice.before(), advice.after(), jumps, mutatedJumps))) {
      return Optional.empty();
    }
    var compared = "`%s %s %s`";
    var left = operands.get(0);
    var right = operands.get(1);
    return Optional.of(
        ("javac compiles %s to %s, a jump past the body when %s: the opposite of the source."
                + " With %s it jumps when %s, so the body runs only when %s")
            .formatted(
                compared.formatted(left, advice.before().get(), right),
                mutant.original(),
                compared.formatted(left, jumps.get(), right),
                mutant.replacement(),
                compared.formatted(left, mutatedJumps.get(), right),
                compared.formatted(left, advice.after().get(), right)));
  }

  private static boolean known(List<Optional<String>> all) {
    return all.stream().allMatch(Optional::isPresent);
  }

  private static List<String> id(Mutant mutant) {
    return List.of(
        ("%s: `m` and the first 8 hex digits of its fingerprint, %s, a SHA-256 of the class,"
                + " method, site and change")
            .formatted(mutant.id(), mutant.fingerprint()),
        "no line number goes in, so the id stays the same when code above it moves");
  }

  private static String site(Mutant mutant) {
    var kind = mutant.operator().onCalls() ? "call site" : "instruction site";
    var counted =
        mutant.operator().onCalls()
            ? "calls are counted in bytecode order"
            : "arithmetic, branches and returns are counted in bytecode order; calls apart";
    return "%s %d of %s%s, counting from 1 (%s)"
        .formatted(kind, mutant.ordinal() + 1, mutant.methodName(), mutant.descriptor(), counted);
  }

  /// ` = bytecode first line`, then the rest dim beneath it, each wrapped under the first.
  private void addRow(List<String> rows, String label, List<String> lines) {
    var first = wrapped(lines.getFirst());
    rows.add(
        "    %s %s  %s"
            .formatted(
                style.dim("="), style.blue(style.bold("%-8s".formatted(label))), first.getFirst()));
    first.stream().skip(1).forEach(line -> rows.add(INDENT + line));
    lines.stream()
        .skip(1)
        .flatMap(line -> wrapped(line).stream())
        .forEach(line -> rows.add(INDENT + style.dim(line)));
  }

  private static List<String> wrapped(String text) {
    var lines = new ArrayList<String>();
    var line = new StringBuilder();
    for (String word : List.of(text.split(" ", -1))) {
      if (line.length() > 0 && INDENT.length() + line.length() + 1 + word.length() > WIDTH) {
        lines.add(line.toString());
        line.setLength(0);
      }
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append(word);
    }
    lines.add(line.toString());
    return lines;
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
