package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.Skip;
import java.util.List;
import java.util.Locale;

/// What `mutastic mutants` prints: each mutant on a line of its own, then how many there are. The
/// line is the Rust edition's, `m855c8e3c src/main/java/com/example/pricing/Pricing.java:17:15
/// mutastic/boundary >= becomes > in Pricing.discount()`: the id, the change's place to the column,
/// the operator, the change in the words a card uses, and the method as the source names it.
///
/// A mutant a run skips as generated isn't listed, nor counted: a tool wrote its code (a
/// framework's `$Bean$Definition`, marked `@Generated` in its class file), so no test is meant to
/// pin it, and a count with it in sizes nothing a run does.
public final class MutantList {
  /// How a skipped mutant's reason starts when a tool wrote its code, as [Skip] says it.
  private static final String GENERATED =
      Skip.Kind.GENERATED.name().toLowerCase(Locale.ROOT) + ": ";

  private final SourceView sources;

  public MutantList(SourceView sources) {
    this.sources = sources;
  }

  /// Each listed mutant's line, in the order given, then `11 mutants`.
  public String of(List<Mutant> mutants) {
    return of(mutants, List.of());
  }

  /// Each listed mutant's line, in the order given, then each of `notes`, then `11 mutants`: the
  /// count stays last, and each mutant's line its own, whatever is said about them.
  public String of(List<Mutant> mutants, List<String> notes) {
    var listed = mutants.stream().filter(MutantList::listed).toList();
    var text = new StringBuilder();
    listed.forEach(mutant -> text.append(line(mutant)).append('\n'));
    notes.forEach(note -> text.append(note).append('\n'));
    return text.append(listed.size()).append(" mutants\n").toString();
  }

  /// Whether the mutant is listed: not when a run skips it as generated.
  static boolean listed(Mutant mutant) {
    return mutant.skipped().filter(reason -> reason.startsWith(GENERATED)).isEmpty();
  }

  /// One mutant's line.
  public String line(Mutant mutant) {
    var place = sources.place(mutant);
    var change = sources.advice(mutant).change().replace("`", "");
    return "%s %s:%d:%d %s %s in %s"
        .formatted(
            mutant.id(),
            sources.file(mutant),
            place.line(),
            place.start(),
            mutant.operator().id(),
            change,
            HumanText.methodLabel(mutant));
  }
}
