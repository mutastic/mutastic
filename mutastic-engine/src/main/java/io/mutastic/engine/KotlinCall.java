package io.mutastic.engine;

import io.mutastic.api.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// How a Kotlin test writes a call of a Kotlin method, or that it can't be written. The JVM's view
/// of a method isn't the source's: `ProbeKt.usesInline` is `usesInline`, `getDoubled()` may be the
/// property `doubled`, an extension's first parameter goes before the dot, and `@JvmName` renames
/// what the source calls something else. A suggested test that doesn't compile is worse than none,
/// so anything not known for sure gets none; the source file, read for the declaration, has the
/// last word on what the method is called there.
///
/// @param kind what the call is made on
/// @param name the method's name as the source writes it
/// @param property whether it's read as a property, with no brackets
/// @param extension whether the first argument goes before the dot
record KotlinCall(Kind kind, String name, boolean property, boolean extension) {
  enum Kind {
    /// A file's top-level function: on nothing.
    TOP_LEVEL,
    /// `@JvmStatic`: on the class.
    ON_THE_CLASS,
    /// On an instance.
    ON_AN_INSTANCE
  }

  private static final Set<Integer> FILE_FACADES = Set.of(2, 4, 5);

  /// How `mutant`'s method is called in Kotlin: empty when it can't be said.
  ///
  /// @param roots the project's source directories, which hold the file it's declared in
  static Optional<KotlinCall> of(ClassModel model, Mutant mutant, List<Path> roots) {
    return model.methods().stream()
        .filter(method -> method.methodName().equalsString(mutant.methodName()))
        .filter(method -> method.methodType().equalsString(mutant.descriptor()))
        .findFirst()
        .flatMap(method -> of(model, method, SourcePaths.of(model, roots).map(KotlinCall::text)));
  }

  private static String text(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Optional<KotlinCall> of(
      ClassModel model, MethodModel method, Optional<String> source) {
    var name = method.methodName().stringValue();
    if (source.isEmpty() || name.contains("$") || name.contains("-") || isGeneric(model, method)) {
      return Optional.empty();
    }
    var kind = kind(model, method);
    boolean extension = isExtension(method);
    if (declaresFunction(source.get(), name)) {
      return Optional.of(new KotlinCall(kind, name, false, extension));
    }
    return propertyName(name)
        .filter(property -> takesOnlyAReceiver(method, extension))
        .filter(property -> declaresProperty(source.get(), property))
        .map(property -> new KotlinCall(kind, property, true, extension));
  }

  private static Kind kind(ClassModel model, MethodModel method) {
    if (!method.flags().has(AccessFlag.STATIC)) {
      return Kind.ON_AN_INSTANCE;
    }
    return isFileFacade(model) ? Kind.TOP_LEVEL : Kind.ON_THE_CLASS;
  }

  /// `k` in `@kotlin.Metadata` says what the class is: a file's top-level declarations are 2, or 4
  /// and 5 for parts of a multi-file class.
  private static boolean isFileFacade(ClassModel model) {
    return model.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
        .flatMap(attribute -> attribute.annotations().stream())
        .filter(annotation -> annotation.className().equalsString("Lkotlin/Metadata;"))
        .flatMap(annotation -> annotation.elements().stream())
        .filter(element -> element.name().equalsString("k"))
        .anyMatch(
            element ->
                element.value() instanceof AnnotationValue.OfInt kind
                    && FILE_FACADES.contains(kind.intValue()));
  }

  /// A generic method or class, whose type arguments a call of numbers wouldn't say: Java takes a
  /// raw type, Kotlin doesn't compile.
  private static boolean isGeneric(ClassModel model, MethodModel method) {
    boolean genericMethod =
        method
            .findAttribute(Attributes.signature())
            .filter(signature -> signature.signature().stringValue().startsWith("<"))
            .isPresent();
    boolean genericClass =
        !method.flags().has(AccessFlag.STATIC)
            && model
                .findAttribute(Attributes.signature())
                .filter(signature -> signature.signature().stringValue().startsWith("<"))
                .isPresent();
    return genericMethod || genericClass;
  }

  /// kotlinc names an extension's receiver in the table of locals: `$this$doubled`.
  private static boolean isExtension(MethodModel method) {
    return method.flags().has(AccessFlag.STATIC)
        && method
            .code()
            .flatMap(code -> code.findAttribute(Attributes.localVariableTable()))
            .stream()
            .flatMap(table -> table.localVariables().stream())
            .anyMatch(
                variable ->
                    variable.slot() == 0 && variable.name().stringValue().startsWith("$this$"));
  }

  /// `doubled` for `getDoubled`, and `isOk` for `isOk`, as Kotlin keeps that prefix.
  private static Optional<String> propertyName(String method) {
    if (method.startsWith("get") && method.length() > 3) {
      return Optional.of(method.substring(3, 4).toLowerCase(Locale.ROOT) + method.substring(4));
    }
    return method.startsWith("is") ? Optional.of(method) : Optional.empty();
  }

  private static boolean takesOnlyAReceiver(MethodModel method, boolean extension) {
    return method.methodTypeSymbol().parameterCount() == (extension ? 1 : 0);
  }

  /// `fun twice(`, `fun <T> twice(`, `fun Int.twice(`.
  private static boolean declaresFunction(String source, String name) {
    return Pattern.compile(
            "\\bfun\\s+(<[^>]*>\\s*)?([\\w.<>?, ]+\\.)?" + Pattern.quote(name) + "\\s*\\(")
        .matcher(source)
        .find();
  }

  /// `val doubled`, `var Int.doubled`.
  private static boolean declaresProperty(String source, String name) {
    return Pattern.compile(
            "\\bva[lr]\\s+(<[^>]*>\\s*)?([\\w.<>?, ]+\\.)?" + Pattern.quote(name) + "\\b")
        .matcher(source)
        .find();
  }

  /// The call: `made` is what it's made on, `Box(4).In(1)`, or the class's name, or nothing.
  String written(String made, List<String> arguments) {
    var given = extension ? arguments.subList(1, arguments.size()) : arguments;
    var on = extension ? receiver(arguments.getFirst()) + "." : made.isEmpty() ? "" : made + ".";
    return on + name + (property ? "" : "(" + String.join(", ", given) + ")");
  }

  /// A negative number needs brackets before a dot.
  private static String receiver(String literal) {
    return literal.startsWith("-") ? "(" + literal + ")" : literal;
  }
}
