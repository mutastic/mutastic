package io.mutastic.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// The input a failing property test says it failed on, read from what it threw: evidence of this
/// run, as another seed draws other inputs. The protocol carries it as how it was drawn and the
/// input on one line, `unshrunk\tArg 0: 18`, and `-\t` for a failure that names none.
///
/// - junit-quickcheck: `With arguments: [18]`, shrunk as the property says (no setting from outside
///   turns that off), or `Unexpected error in property … with args [18] and seeds […]`.
/// - QuickTheories: the lines after `Smallest found falsifying value(s) :-`.
/// - Kotest: each `Arg 0: 18` line, without its `(shrunk from …)`.
/// - proptastic: the fact on the line after its report's header, `shrunk counterexample: xs = [0,
///   1]`, or without `shrunk` where it didn't shrink. Stacked, the key ends its line and the value
///   is the lines below it, four spaces in. Only from its failure, by the class's name: the tests'
///   copy of it isn't mutastic's to load.
final class CaughtInput {
  /// What the protocol says for a failure that names no input.
  static final String NONE = "-\t";

  /// The most of an input the protocol carries, in characters, before `…`: a property can draw a
  /// long list.
  static final int LONGEST = 300;

  private static final String QUICKCHECK = "With arguments: ";

  private static final Pattern QUICKCHECK_ERROR =
      Pattern.compile(
          "^Unexpected error in property \\S+ with args (?<args>.*) and seeds \\[[-0-9, ]*\\]$",
          Pattern.DOTALL);

  private static final String QUICKTHEORIES = "Smallest found falsifying value(s) :-";

  private static final Pattern QUICKTHEORIES_END =
      Pattern.compile("^(?:Other found falsifying value\\(s\\) :-|Cause was :-).*");

  private static final String KOTEST = "Property failed after ";

  private static final String PROPTASTIC_FAILURE = "io.proptastic.core.PropertyFailure";

  /// proptastic's header: the test's name, then how it failed. `after N tries` is the one form a
  /// run with shrinking on makes smaller; a pinned example or a flaky input never shrinks.
  private static final Pattern PROPTASTIC_HEADER =
      Pattern.compile("^\\S+ failed (?<how>after \\d+ tr(?:y|ies) \\(seed 0x[0-9a-f]+\\)|on .*)$");

  private static final Pattern PROPTASTIC_FACT =
      Pattern.compile("^ {2}(?<shrunk>shrunk )?counterexample:(?: (?<input>.*))?$");

  /// The indent of a stacked fact's value.
  private static final String STACKED = "    ";

  private static final Pattern KOTEST_ARG =
      Pattern.compile("^\\s*(?<arg>Arg \\d+: .*?)(?: \\(shrunk from .*\\))?$");

  private CaughtInput() {}

  /// How the protocol says what `thrown`, or a cause of it, names as the input, or [#NONE].
  /// `unshrunk` when it's from a framework whose shrinking this run turned off, so a run with it on
  /// would name a smaller one; `shrunk` otherwise.
  static String of(Throwable thrown, boolean shrinkingOff) {
    for (Throwable cause : chain(thrown)) {
      var said = cause.getMessage();
      if (said == null) {
        continue;
      }
      var quickcheck = quickcheck(said);
      if (quickcheck.isPresent()) {
        return "shrunk\t" + oneLine(quickcheck.get());
      }
      var shrinkable = quickTheories(said).or(() -> kotest(said));
      if (shrinkable.isPresent()) {
        return (shrinkingOff ? "unshrunk\t" : "shrunk\t") + oneLine(shrinkable.get());
      }
      var proptastic = proptastic(cause, said);
      if (proptastic.isPresent()) {
        return proptastic.get();
      }
    }
    return NONE;
  }

  /// The exception and its causes, outermost first, each once.
  private static List<Throwable> chain(Throwable thrown) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    var chain = new ArrayList<Throwable>();
    for (Throwable cause = thrown; cause != null && visited.add(cause); cause = cause.getCause()) {
      chain.add(cause);
    }
    return chain;
  }

  static Optional<String> quickcheck(String said) {
    var error = QUICKCHECK_ERROR.matcher(said);
    if (error.matches()) {
      return Optional.of(error.group("args"));
    }
    return said.lines()
        .filter(line -> line.startsWith(QUICKCHECK))
        .map(line -> line.substring(QUICKCHECK.length()))
        .findFirst();
  }

  static Optional<String> quickTheories(String said) {
    var lines = said.lines().toList();
    int header = lines.indexOf(QUICKTHEORIES);
    if (header < 0) {
      return Optional.empty();
    }
    var value =
        lines.subList(header + 1, lines.size()).stream()
            .takeWhile(line -> !QUICKTHEORIES_END.matcher(line).matches())
            .collect(Collectors.joining("\n"));
    return Optional.of(value).filter(text -> !text.isBlank());
  }

  /// proptastic's counterexample, as the protocol says it: `unshrunk` when it was found after
  /// random tries and not shrunk, as on a mutant's run.
  static Optional<String> proptastic(Throwable thrown, String said) {
    if (!thrown.getClass().getName().equals(PROPTASTIC_FAILURE)) {
      return Optional.empty();
    }
    var lines = said.lines().toList();
    for (int at = 1; at < lines.size(); at++) {
      var header = PROPTASTIC_HEADER.matcher(lines.get(at - 1));
      var fact = PROPTASTIC_FACT.matcher(lines.get(at));
      if (header.matches() && fact.matches()) {
        var input = fact.group("input");
        var value =
            input == null || input.isBlank() ? stacked(lines.subList(at + 1, lines.size())) : input;
        boolean unshrunk = fact.group("shrunk") == null && header.group("how").startsWith("after ");
        return Optional.of((unshrunk ? "unshrunk\t" : "shrunk\t") + oneLine(value));
      }
    }
    return Optional.empty();
  }

  /// A stacked fact's value: the lines four spaces in, up to the next fact.
  private static String stacked(List<String> below) {
    return below.stream()
        .takeWhile(line -> line.startsWith(STACKED))
        .collect(Collectors.joining("\n"));
  }

  static Optional<String> kotest(String said) {
    if (!said.startsWith(KOTEST)) {
      return Optional.empty();
    }
    var args =
        said.lines()
            .map(KOTEST_ARG::matcher)
            .filter(java.util.regex.Matcher::matches)
            .map(arg -> arg.group("arg"))
            .collect(Collectors.joining(", "));
    return Optional.of(args).filter(text -> !text.isEmpty());
  }

  /// On one line, as the protocol needs, a test class's copy named as the build names it, and no
  /// longer than [#LONGEST] characters, then `…`.
  static String oneLine(String input) {
    var line =
        Renamer.original(
            input
                .lines()
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "))
                .replace('\t', ' '));
    if (line.codePointCount(0, line.length()) <= LONGEST) {
      return line;
    }
    return line.substring(0, line.offsetByCodePoints(0, LONGEST)) + "…";
  }
}
