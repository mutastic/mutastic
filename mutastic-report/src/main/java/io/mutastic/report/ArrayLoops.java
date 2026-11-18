package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// The cards for a change to the test at the top of a for-each loop over an array. javac writes the
/// loop as an index counted up to the array's length, in locals of its own the source doesn't name,
/// so the test itself is no help on a card: `local6 < local5` is always true. What the change does
/// to the loop is: it reads past the last element, or never runs its body.
///
/// The engine gives such a test one operand, the loop as the source has it: `for (… : items)`.
final class ArrayLoops {
  /// The operand of a for-each loop's test: the array, or `…` when its source can't be read.
  private static final Pattern LOOP = Pattern.compile("for \\(… : (?<array>.+)\\)");

  private ArrayLoops() {}

  /// The advice for a mutant of a for-each loop's test, if it's one.
  static Optional<Advice> of(Mutant mutant, Inputs seen) {
    var operands = mutant.operands();
    if (operands.size() != 1 || !Advice.onJump(mutant.operator())) {
      return Optional.empty();
    }
    var found = LOOP.matcher(operands.getFirst());
    if (!found.matches()) {
      return Optional.empty();
    }
    var array = found.group("array");
    var loop =
        new Loop(Advice.method(mutant), array.equals("…") ? Optional.empty() : Optional.of(array));
    var notes = Advice.inputsSeen(seen);
    return Optional.of(
        switch (mutant.replacement()) {
          // `i <= n`: the index never passes the length, so this is the test always true.
          case "never jump", "IF_ICMPGT" -> pastTheEnd(loop, notes);
          // `i > n`: never true of an index from 0, so the test is always false.
          case "always jump", "IF_ICMPLE" -> neverRuns(loop, notes);
          // `i >= n` and `i == n`: true at once only for an empty array.
          case "IF_ICMPLT", "IF_ICMPNE" -> onlyWhenEmpty(loop, notes);
          default -> elsewhere(loop, notes);
        });
  }

  /// The method the loop is in, and the array as the source writes it, if it can be read.
  private record Loop(String method, Optional<String> array) {
    /// "the for-each loop over `items`".
    String name() {
      return "the for-each loop over " + array.map("`%s`"::formatted).orElse("an array");
    }

    /// "`items`", or "the array".
    String arrayName() {
      return array.map("`%s`"::formatted).orElse("the array");
    }
  }

  /// The test always true: the loop goes on past the last element, and reading it throws.
  private static Advice pastTheEnd(Loop loop, List<String> notes) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s reads past its last element, an ArrayIndexOutOfBoundsException".formatted(loop.name()),
        "reads past the end",
        "no test runs %s to its end in %s".formatted(loop.name(), loop.method()),
        notes,
        "add a test where %s runs to its end, and assert what %s does then"
            .formatted(loop.name(), loop.method()));
  }

  /// The test always false: the loop stops before its first element.
  private static Advice neverRuns(Loop loop, List<String> notes) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s never runs its body".formatted(loop.name()),
        "body never runs",
        "no test needs %s to run its body in %s".formatted(loop.name(), loop.method()),
        notes,
        "add a test where %s has an element, and assert what %s does then"
            .formatted(loop.arrayName(), loop.method()));
  }

  /// `i < n` as `i >= n`: an index from 0 is past the end only of an empty array, so the loop stops
  /// at once for any other, and reads the first element of an empty one.
  private static Advice onlyWhenEmpty(Loop loop, List<String> notes) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s never runs its body, and reads past the end of an empty array".formatted(loop.name()),
        "body never runs",
        "no test needs %s to run its body, or gives it an empty array, in %s"
            .formatted(loop.name(), loop.method()),
        notes,
        "add a test where %s is empty, and assert what %s does then"
            .formatted(loop.arrayName(), loop.method()));
  }

  /// `i < n` as `i != n`, which an index from 0 up to the length can't tell apart: the engine skips
  /// it as equivalent.
  private static Advice elsewhere(Loop loop, List<String> notes) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s tests for its end another way".formatted(loop.name()),
        "end test changed",
        "no test checks where %s stops in %s".formatted(loop.name(), loop.method()),
        notes,
        "add a test where %s runs to its end, and assert what %s does then"
            .formatted(loop.name(), loop.method()));
  }
}
