package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Project;
import io.mutastic.api.Skip;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.Optional;
import java.util.Set;

/// What a team says in its code: `@DoNotMutate` or `@CoverageIgnore` on a method or class skips its
/// mutants, with the annotation's `value()` or `reason()` as the reason; `@Generated` marks code a
/// tool wrote, in the class file or, as the JDK's own is kept for the source alone, on the type in
/// its source ([SourceGenerated]). By simple name, so any annotation of the name works.
final class AnnotatedSkips implements Filter {
  private static final Set<String> USER = Set.of("DoNotMutate", "CoverageIgnore");
  private static final Set<String> GENERATED = Set.of("Generated");

  private SourceGenerated sources = SourceGenerated.NONE;

  @Override
  public String id() {
    return "mutastic:annotations";
  }

  /// Says where the project's sources are, for a `@Generated` only they hold.
  void prepareFor(Project project) {
    sources = new SourceGenerated(project.sources());
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    var where = "`%s()`".formatted(method.methodName().stringValue());
    return skip(
            Annotations.find(owner, method, USER),
            Annotations.find(owner, method, GENERATED),
            where)
        .or(() -> inTheSource(owner));
  }

  /// The same for what's on a class but not in a method: an annotation on the class decides.
  Optional<Skip> skipClass(ClassModel owner, String member) {
    return skip(
            Annotations.find(owner, USER), Annotations.find(owner, GENERATED), "`" + member + "`")
        .or(() -> inTheSource(owner));
  }

  /// `@Generated on `Order` in the source`, when only the source says so.
  private Optional<Skip> inTheSource(ClassModel owner) {
    return sources.marks(owner)
        ? Optional.of(
            new Skip(
                Skip.Kind.GENERATED,
                "@Generated on `%s` in the source"
                    .formatted(SourceGenerated.outermost(owner.thisClass().asInternalName()))))
        : Optional.empty();
  }

  private static Optional<Skip> skip(
      Optional<Annotations.Found> byUser, Optional<Annotations.Found> generated, String where) {
    return byUser
        .map(
            found ->
                new Skip(
                    Skip.Kind.USER,
                    "@%s on %s%s"
                        .formatted(
                            found.simpleName(),
                            where,
                            found.reason().map(": "::concat).orElse(""))))
        .or(() -> generated.map(found -> new Skip(Skip.Kind.GENERATED, "@Generated on " + where)));
  }
}
