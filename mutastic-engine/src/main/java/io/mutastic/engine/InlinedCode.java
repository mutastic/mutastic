package io.mutastic.engine;

import io.mutastic.api.Mutant;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Mutants in code kotlinc copied in from an inline function, put where that code really is. From
/// the module's own inline function, it's the line of that function: a test of the call catches it
/// or doesn't, and it's the function's code that's tested. From anywhere else, it's nothing the
/// person wrote and no test of theirs is meant to pin it: the loop in the standard library's
/// `filter`, say.
final class InlinedCode {
  private InlinedCode() {}

  /// `found`, in `model`'s class, moved and skipped as the class's map says. A class with no map
  /// has its mutants as they are.
  ///
  /// @param module the internal names of the module's classes
  static List<Mutant> located(ClassModel model, Set<String> module, List<Mutant> found) {
    var lines = SourceMap.of(model);
    return found.stream()
        .map(
            mutant ->
                lines
                    .copied(mutant.line())
                    .map(place -> at(place, mutant, model, module))
                    .orElse(mutant))
        .toList();
  }

  private static Mutant at(
      SourceMap.Place place, Mutant mutant, ClassModel model, Set<String> module) {
    var moved = mutant.at(place.line(), otherFile(place, model));
    boolean own =
        place.owner().equals(model.thisClass().asInternalName()) || module.contains(place.owner());
    return own || mutant.skipped().isPresent()
        ? moved
        : moved.skippedFor(
            "generated: kotlinc copied this from %s, which isn't this module's"
                .formatted(place.owner().replace('/', '.')));
  }

  /// The place's file under a source root, unless it's the file the class was compiled from.
  static Optional<String> otherFile(SourceMap.Place place, ClassModel model) {
    var internal = model.thisClass().asInternalName();
    var directory = internal.substring(0, internal.lastIndexOf('/') + 1);
    boolean own =
        model
            .findAttribute(Attributes.sourceFile())
            .filter(file -> (directory + file.sourceFile().stringValue()).equals(place.path()))
            .isPresent();
    return own ? Optional.empty() : Optional.of(place.path());
  }
}
