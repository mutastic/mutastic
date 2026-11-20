package io.mutastic.api;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.List;
import java.util.Optional;

/// Decides that a method's mutants, or one of them, shouldn't run, and says why. A filter acts only
/// on positive evidence in the bytecode, never on a name alone: one that skips too much makes the
/// score lie, and nobody can tell.
public interface Filter {
  /// `mutastic:trivial-accessors`: how configuration and audits name it.
  String id();

  /// Sees the whole module's classes once, before any [#skip]: a filter that judges a method by who
  /// calls it needs them all.
  default void prepare(List<ClassModel> module) {}

  /// Why `method`'s mutants shouldn't run, if they shouldn't.
  Optional<Skip> skip(ClassModel owner, MethodModel method);

  /// Why one mutant shouldn't run, if it shouldn't: for a rule narrower than a method, such as
  /// "this value only goes into a log line". Asked of each mutant still to run once mutastic's own
  /// checks and every filter's [#skip] have had their say, so a method skipped as a whole never
  /// gets here. `site` is the instruction the mutant changes, with the code around it, and `mutant`
  /// is the change. None by default: the method's verdict stands.
  ///
  /// A skip says why, as every skip does: a [Skip] with a blank reason stops the run.
  default Optional<Skip> skipMutant(Mutator.Site site, Mutant mutant) {
    return Optional.empty();
  }
}
