package io.mutastic.cli;

import io.mutastic.api.Mutant;
import io.mutastic.report.Invocation;
import io.mutastic.report.SourceView;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/// The mutants on the lines `--at` asks for. A mutant is on a line when the line is one its card
/// points at ([SourceView#span]): javac's line, where its statement starts, to the line its change
/// is on. So a mutant on a statement split over lines is on each of them, and a line has the
/// mutants a card shows on it. Lines are read from the file as it is on disk, as the cards are.
///
/// Lines with no mutant are never a run of none: they're refused, with the nearest lines that have
/// one and the command that lists them, as a typo in a line number would otherwise pass in silence.
final class AtMutants {
  private AtMutants() {}

  /// What lines with no mutant on them are called.
  static final String NONE = "no-mutant-at";

  /// The most lines a refusal names.
  private static final int NEAREST = 5;

  /// Of `found`, in its order, those on any of `asked`; refused when one of them has none.
  static List<Mutant> on(List<AtFiles.Lines> asked, List<Mutant> found, SourceView sources) {
    SequencedSet<Mutant> chosen = new LinkedHashSet<>();
    for (var lines : asked) {
      var inFile = inFile(lines, found, sources);
      var on =
          inFile.stream()
              .filter(mutant -> sources.span(mutant).overlaps(lines.at().from(), lines.at().to()))
              .toList();
      if (on.isEmpty()) {
        throw none(lines, inFile, sources);
      }
      chosen.addAll(on);
    }
    return found.stream().filter(chosen::contains).toList();
  }

  /// `found`'s mutants in the file `lines` is of.
  private static List<Mutant> inFile(AtFiles.Lines lines, List<Mutant> found, SourceView sources) {
    return found.stream()
        .filter(mutant -> sources.path(mutant).filter(lines.file()::equals).isPresent())
        .toList();
  }

  /// `no mutant on Pricing.java:16; there are on 17, 18 and 20`, or `there's one on 17` for one
  /// line, and the command that lists them; or, with none in the file, the command that lists the
  /// module's. Said as typed: a failure's lines are escaped where they're shown.
  private static Main.UsageException none(
      AtFiles.Lines lines, List<Mutant> inFile, SourceView sources) {
    var asked = lines.at().typed(lines.name());
    var invocation = withoutOptions(sources.invocation());
    if (inFile.isEmpty()) {
      return new Main.UsageException(
          NONE,
          "no mutant on %s, nor on any other line of %s. To see the module's mutants:\n  %s"
              .formatted(asked, lines.name(), invocation.command(List.of("mutants"))));
    }
    var nearest = nearest(lines.at(), inFile, sources);
    var listed = Options.listed(nearest.stream().map(String::valueOf).toList(), "and");
    var around = new At(lines.at().path(), nearest.getFirst(), nearest.getLast());
    return new Main.UsageException(
        NONE,
        "no mutant on %s; %s. To see %s:\n  %s"
            .formatted(
                asked,
                nearest.size() == 1 ? "there's one on " + listed : "there are on " + listed,
                nearest.size() == 1 ? "it" : "them",
                invocation.command(List.of("mutants", "--at=" + around.typed(lines.shown())))));
  }

  /// The lines with a mutant nearest those asked for, at most [#NEAREST], in order: each mutant's
  /// as its card and `mutants` name it.
  private static List<Integer> nearest(At at, List<Mutant> inFile, SourceView sources) {
    Comparator<Integer> byDistance =
        Comparator.comparingInt(line -> Math.max(at.from() - line, line - at.to()));
    return inFile.stream()
        .map(mutant -> sources.place(mutant).line())
        .distinct()
        .sorted(byDistance.thenComparing(Comparator.naturalOrder()))
        .limit(NEAREST)
        .sorted()
        .toList();
  }

  /// The command for the module alone: `mutants` takes no option a run's target can carry, such as
  /// `--suite`.
  private static Invocation withoutOptions(Invocation invocation) {
    var target = invocation.target().stream().filter(word -> !word.startsWith("--")).toList();
    return new Invocation(invocation.form(), invocation.launcher(), target, invocation.build());
  }
}
