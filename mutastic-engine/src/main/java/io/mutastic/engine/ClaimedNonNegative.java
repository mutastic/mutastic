package io.mutastic.engine;

import static java.lang.constant.ConstantDescs.CD_int;

import io.mutastic.api.Operator;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.reflect.AccessFlag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/// A comparison's mutant that differs only for a negative value the code says is never negative:
/// `count > 0` becoming `count != 0`, where `count` is `@NonNegative`.
///
/// This rests on the project's word, not on mutastic's proof, so the reason names the annotation.
/// Only an annotation a tool checks at compile time counts: the Checker Framework's `@NonNegative`,
/// `@Positive` and `@IndexFor`, and AndroidX's `@IntRange` with a `from` of 0 or more, which
/// Android Lint checks. Jakarta Validation's `@PositiveOrZero` and `@Min(0)` don't: they're checked
/// only where something validates the value, so a direct call can pass -1. Nor does JSR-305's
/// `@Nonnegative`, which nothing checks.
///
/// The value is read straight before the jump: a parameter nothing stores to in the method, a local
/// annotated where it's read and not stored to in that range, an `int` field, or what a method
/// returns, the method found in the class itself or the module. A value reassigned is no longer the
/// one the annotation is on.
final class ClaimedNonNegative {
  /// The Checker Framework's, by descriptor. Each is a type annotation, so it's in a type
  /// annotations attribute, kept at run time.
  private static final Set<String> CHECKED =
      Set.of(
          "Lorg/checkerframework/checker/index/qual/NonNegative;",
          "Lorg/checkerframework/checker/index/qual/Positive;",
          "Lorg/checkerframework/checker/index/qual/IndexFor;");

  /// AndroidX's, a declaration annotation kept only in the class file. Its `from` is
  /// `Long.MIN_VALUE` unless given, which says nothing.
  private static final String INT_RANGE = "Landroidx/annotation/IntRange;";

  private ClaimedNonNegative() {}

  /// The comparison at `code[at]`, in `method` of `model`; `classes` finds a class of the module by
  /// its internal name, for a field or method of another.
  record Site(
      ClassModel model,
      MethodModel method,
      List<CodeElement> code,
      int at,
      Function<String, Optional<ClassModel>> classes) {}

  /// Why the mutant `operator` makes, `replacement`, shouldn't run: it differs only where the value
  /// compared, `value` as the source names it, is negative, and the code says it's never negative.
  /// None for `value` where the class file has no names.
  static Optional<String> reason(
      Operator operator, String replacement, Site site, Optional<String> value) {
    if (!Comparisons.changesAComparison(operator)
        || !(site.code().get(site.at()) instanceof BranchInstruction branch)
        || !Comparisons.onlyBelowZero(branch.opcode(), replacement)) {
      return Optional.empty();
    }
    var before = ImpliedOr.instructionsBefore(site.code(), site.at(), 1);
    if (before.isEmpty()) {
      return Optional.empty();
    }
    return claim(site, before.getFirst()).map(claim -> why(value, claim));
  }

  /// The two-line reason, naming the value where the source's name for it is known.
  private static String why(Optional<String> value, String claim) {
    return value
        .map(
            name ->
                "equivalent: differs only when %s is negative. That can't happen because %s is %s."
                    .formatted(name, name, claim))
        .orElse(
            "equivalent: differs only when the value compared is negative. That can't happen"
                + " because it's "
                + claim
                + ".");
  }

  /// What the code says of the value `instruction` pushes, as the annotation's written:
  /// `@NonNegative`.
  private static Optional<String> claim(Site site, Instruction instruction) {
    return switch (instruction) {
      case LoadInstruction load when load.typeKind() == TypeKind.INT -> local(site, load.slot());
      case FieldInstruction field
          when (field.opcode() == Opcode.GETFIELD || field.opcode() == Opcode.GETSTATIC)
              && field.type().equalsString("I") ->
          field(site, field);
      case InvokeInstruction call when call.typeSymbol().returnType().equals(CD_int) ->
          returned(site, call);
      default -> Optional.empty();
    };
  }

  /// A parameter's, or a local's where it's read.
  private static Optional<String> local(Site site, int slot) {
    var parameter = parameterAt(site.method(), slot);
    if (parameter.isPresent()) {
      return storedTo(site.code(), slot, 0, site.code().size())
          ? Optional.empty()
          : parameterClaim(site.method(), parameter.getAsInt());
    }
    return localClaim(site, slot);
  }

  /// Which parameter is in `slot`, counting `this` and the two slots of a `long` or `double`.
  private static OptionalInt parameterAt(MethodModel method, int slot) {
    int at = method.flags().has(AccessFlag.STATIC) ? 0 : 1;
    var parameters = method.methodTypeSymbol().parameterList();
    for (int index = 0; index < parameters.size(); index++) {
      if (at == slot) {
        return OptionalInt.of(index);
      }
      at += TypeKind.from(parameters.get(index)).slotSize();
    }
    return OptionalInt.empty();
  }

  /// A type annotation on the parameter, or a declaration annotation where javac lists one for each
  /// parameter the descriptor has.
  private static Optional<String> parameterClaim(MethodModel method, int index) {
    var typed =
        typeAnnotations(method)
            .filter(
                annotation ->
                    annotation.targetInfo() instanceof TypeAnnotation.FormalParameterTarget target
                        && target.formalParameterIndex() == index)
            .map(TypeAnnotation::annotation);
    int count = method.methodTypeSymbol().parameterCount();
    var declared =
        Stream.concat(
                method.findAttribute(Attributes.runtimeVisibleParameterAnnotations()).stream()
                    .map(attribute -> attribute.parameterAnnotations()),
                method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations()).stream()
                    .map(attribute -> attribute.parameterAnnotations()))
            .filter(each -> each.size() == count)
            .flatMap(each -> each.get(index).stream());
    return firstClaim(Stream.concat(typed, declared));
  }

  /// A type annotation on a local whose range covers the comparison and that nothing stores to
  /// within it. A declaration annotation on a local isn't in the class file.
  private static Optional<String> localClaim(Site site, int slot) {
    var positions = new HashMap<Label, Integer>();
    for (int index = 0; index < site.code().size(); index++) {
      if (site.code().get(index) instanceof LabelTarget target) {
        positions.put(target.label(), index);
      }
    }
    return firstClaim(
        site.code().stream()
            .flatMap(ClaimedNonNegative::typeAnnotationsIn)
            .filter(annotation -> annotation.targetPath().isEmpty())
            .filter(
                annotation ->
                    annotation.targetInfo() instanceof TypeAnnotation.LocalVarTarget target
                        && target.table().stream()
                            .anyMatch(range -> covers(site, positions, range, slot)))
            .map(TypeAnnotation::annotation));
  }

  /// Whether the range is `slot`'s, covers the comparison, and has no store to it.
  private static boolean covers(
      Site site, Map<Label, Integer> positions, TypeAnnotation.LocalVarTargetInfo range, int slot) {
    var start = positions.get(range.startLabel());
    var end = positions.get(range.endLabel());
    return range.index() == slot
        && start != null
        && end != null
        && start < site.at()
        && site.at() < end
        && !storedTo(site.code(), slot, start, end);
  }

  /// Whether anything in `code[from, to)` stores to `slot`, a wide store to the slot before
  /// included.
  private static boolean storedTo(List<CodeElement> code, int slot, int from, int to) {
    for (int index = from; index < to; index++) {
      switch (code.get(index)) {
        case StoreInstruction store
            when store.slot() <= slot && slot < store.slot() + store.typeKind().slotSize() -> {
          return true;
        }
        case IncrementInstruction increment when increment.slot() == slot -> {
          return true;
        }
        default -> {}
      }
    }
    return false;
  }

  /// An `int` field's, of the class itself or another in the module.
  private static Optional<String> field(Site site, FieldInstruction read) {
    return classNamed(site, read.owner())
        .flatMap(
            owner ->
                owner.fields().stream()
                    .filter(field -> field.fieldName().equals(read.name()))
                    .filter(field -> field.fieldType().equals(read.type()))
                    .findFirst())
        .flatMap(ClaimedNonNegative::fieldClaim);
  }

  private static Optional<String> fieldClaim(FieldModel field) {
    return firstClaim(
        Stream.concat(
            declarationAnnotations(field),
            typeAnnotations(field)
                .filter(
                    annotation ->
                        annotation.targetInfo().targetType() == TypeAnnotation.TargetType.FIELD)
                .map(TypeAnnotation::annotation)));
  }

  /// What the method called says it returns, found in its class or a superclass in the module.
  private static Optional<String> returned(Site site, InvokeInstruction call) {
    var owner = classNamed(site, call.owner());
    for (int depth = 0; owner.isPresent() && depth < 16; depth++) {
      var found =
          owner.get().methods().stream()
              .filter(method -> method.methodName().equals(call.name()))
              .filter(method -> method.methodType().equals(call.type()))
              .findFirst();
      if (found.isPresent()) {
        return returnClaim(found.get());
      }
      owner = owner.get().superclass().flatMap(superclass -> classNamed(site, superclass));
    }
    return Optional.empty();
  }

  private static Optional<String> returnClaim(MethodModel method) {
    return firstClaim(
        Stream.concat(
            declarationAnnotations(method),
            typeAnnotations(method)
                .filter(
                    annotation ->
                        annotation.targetInfo().targetType()
                            == TypeAnnotation.TargetType.METHOD_RETURN)
                .map(TypeAnnotation::annotation)));
  }

  private static Optional<ClassModel> classNamed(Site site, ClassEntry owner) {
    return site.classes().apply(owner.asInternalName());
  }

  /// The first annotation that counts, as written: `@NonNegative`, `@IntRange(from = 0)`.
  private static Optional<String> firstClaim(Stream<Annotation> annotations) {
    return annotations.map(ClaimedNonNegative::claimed).flatMap(Optional::stream).findFirst();
  }

  private static Optional<String> claimed(Annotation annotation) {
    var descriptor = annotation.className().stringValue();
    if (CHECKED.contains(descriptor)) {
      return Optional.of(
          "@" + descriptor.substring(descriptor.lastIndexOf('/') + 1, descriptor.length() - 1));
    }
    if (!descriptor.equals(INT_RANGE)) {
      return Optional.empty();
    }
    return annotation.elements().stream()
        .filter(element -> element.name().equalsString("from"))
        .map(element -> element.value())
        .flatMap(
            value ->
                value instanceof AnnotationValue.OfLong from && from.longValue() >= 0
                    ? Stream.of("@IntRange(from = " + from.longValue() + ")")
                    : Stream.empty())
        .findFirst();
  }

  private static Stream<Annotation> declarationAnnotations(AttributedElement element) {
    return Stream.concat(
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()),
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()));
  }

  /// The type annotations on a field or method, the ones on the type itself: `@NonNegative int`,
  /// not `@NonNegative int[]`'s element.
  private static Stream<TypeAnnotation> typeAnnotations(AttributedElement element) {
    return Stream.concat(
            element.findAttribute(Attributes.runtimeVisibleTypeAnnotations()).stream()
                .flatMap(attribute -> attribute.annotations().stream()),
            element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).stream()
                .flatMap(attribute -> attribute.annotations().stream()))
        .filter(annotation -> annotation.targetPath().isEmpty());
  }

  /// The type annotations a method's code holds, as elements of it.
  private static Stream<TypeAnnotation> typeAnnotationsIn(CodeElement element) {
    return switch (element) {
      case RuntimeVisibleTypeAnnotationsAttribute visible -> visible.annotations().stream();
      case RuntimeInvisibleTypeAnnotationsAttribute invisible -> invisible.annotations().stream();
      default -> Stream.empty();
    };
  }
}
