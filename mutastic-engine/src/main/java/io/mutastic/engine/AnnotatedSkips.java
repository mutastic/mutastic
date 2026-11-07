package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Skip;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.Optional;
import java.util.Set;

/// What a team says in its code: `@DoNotMutate` or `@CoverageIgnore` on a method or class skips its
/// mutants, with the annotation's `value()` or `reason()` as the reason; `@Generated` marks code a
/// tool wrote. By simple name, so any annotation of the name works.
final class AnnotatedSkips implements Filter {
  private static final Set<String> USER = Set.of("DoNotMutate", "CoverageIgnore");
  private static final Set<String> GENERATED = Set.of("Generated");

  @Override
  public String id() {
    return "mutastic:annotations";
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    var where = "`%s()`".formatted(method.methodName().stringValue());
    return skip(
        Annotations.find(owner, method, USER), Annotations.find(owner, method, GENERATED), where);
  }

  /// The same for what's on a class but not in a method: an annotation on the class decides.
  Optional<Skip> skipClass(ClassModel owner, String member) {
    return skip(
        Annotations.find(owner, USER), Annotations.find(owner, GENERATED), "`" + member + "`");
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
