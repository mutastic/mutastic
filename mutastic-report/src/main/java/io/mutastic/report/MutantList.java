package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.List;

/// What `mutastic mutants` prints: each mutant on a line of its own, then how many there are. The
/// line is the Rust edition's, `m855c8e3c src/main/java/com/example/pricing/Pricing.java:17:15
/// mutastic/boundary >= becomes > in Pricing.discount()`: the id, the change's place to the column,
/// the operator, the change in the words a card uses, and the method as the source names it.
public final class MutantList {
  private final SourceView sources;

  public MutantList(SourceView sources) {
    this.sources = sources;
  }

  /// Every mutant's line, in the order given, then `11 mutants`.
  public String of(List<Mutant> mutants) {
    return of(mutants, List.of());
  }

  /// Every mutant's line, in the order given, then each of `notes`, then `11 mutants`: the count
  /// stays last, and each mutant's line its own, whatever is said about them.
  public String of(List<Mutant> mutants, List<String> notes) {
    var text = new StringBuilder();
    mutants.forEach(mutant -> text.append(line(mutant)).append('\n'));
    notes.forEach(note -> text.append(note).append('\n'));
    return text.append(mutants.size()).append(" mutants\n").toString();
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
