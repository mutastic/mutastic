package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.util.Optional;

/// Whether a test class could write the call a suggestion makes, and how. The worker calls methods
/// by reflection and can reach anything; a test can only reach what Java's access rules allow from
/// its package, and a suggested test that doesn't compile is worse than none.
final class Access {
  private Access() {}

  /// How code in `testClass` (a binary name) can call the mutant's method, if it can: `-` for a
  /// static method, else the descriptor of the constructor to make the receiver with. That's the
  /// no-argument constructor when there's one it can reach, or else one whose parameters are all
  /// whole numbers (`int`, `long`, `Integer`, `Long`), as a record of numbers has.
  static Optional<String> receiver(ClassModel model, Mutant mutant, String testClass) {
    boolean samePackage =
        packageOf(model.thisClass().asInternalName().replace('/', '.'))
            .equals(packageOf(testClass));
    var method = method(model, mutant.methodName(), mutant.descriptor());
    if (method.isEmpty()
        || !reachable(method.get().flags().flags(), samePackage)
        || !classReachable(model, samePackage)) {
      return Optional.empty();
    }
    if (method.get().flags().has(AccessFlag.STATIC)) {
      return Optional.of("-");
    }
    if (model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)) {
      return Optional.empty();
    }
    var noArguments =
        method(model, "<init>", "()V")
            .filter(constructor -> reachable(constructor.flags().flags(), samePackage));
    if (noArguments.isPresent()) {
      return Optional.of("()V");
    }
    return wholeNumberConstructor(model, samePackage);
  }

  /// The reachable constructor with the fewest parameters, all of them whole numbers.
  private static Optional<String> wholeNumberConstructor(ClassModel model, boolean samePackage) {
    return model.methods().stream()
        .filter(constructor -> constructor.methodName().equalsString("<init>"))
        .filter(constructor -> reachable(constructor.flags().flags(), samePackage))
        .filter(
            constructor ->
                constructor.methodTypeSymbol().parameterCount() > 0
                    && constructor.methodTypeSymbol().parameterList().stream()
                        .allMatch(type -> WHOLE_NUMBERS.contains(type.descriptorString())))
        .min(
            java.util.Comparator.comparingInt(
                (MethodModel constructor) -> constructor.methodTypeSymbol().parameterCount()))
        .map(constructor -> constructor.methodType().stringValue());
  }

  /// Whether code in `testClass` can call the mutant's method at all.
  static boolean callableFrom(ClassModel model, Mutant mutant, String testClass) {
    return receiver(model, mutant, testClass).isPresent();
  }

  /// Parameter types a suggestion can fill with a number.
  static final java.util.Set<String> WHOLE_NUMBERS =
      java.util.Set.of("I", "J", "Ljava/lang/Integer;", "Ljava/lang/Long;");

  /// Public from anywhere; package-private and protected from the same package; private never.
  private static boolean reachable(java.util.Set<AccessFlag> flags, boolean samePackage) {
    return flags.contains(AccessFlag.PUBLIC)
        || (!flags.contains(AccessFlag.PRIVATE) && samePackage);
  }

  /// A top-level class by its own flags; a nested one by the access it was declared with, which
  /// javac keeps in the InnerClasses attribute (its own flags say public or package only).
  private static boolean classReachable(ClassModel model, boolean samePackage) {
    var self = model.thisClass().asInternalName();
    var declared =
        model.findAttribute(Attributes.innerClasses()).stream()
            .flatMap(attribute -> attribute.classes().stream())
            .filter(inner -> inner.innerClass().asInternalName().equals(self))
            .findFirst();
    if (declared.isPresent()) {
      // A non-static inner class needs an outer instance: `new Outer().new Inner()` isn't written.
      boolean isStatic = declared.get().flags().contains(AccessFlag.STATIC);
      return isStatic && reachable(declared.get().flags(), samePackage);
    }
    return reachable(model.flags().flags(), samePackage);
  }

  private static Optional<MethodModel> method(ClassModel model, String name, String descriptor) {
    return model.methods().stream()
        .filter(
            method ->
                method.methodName().equalsString(name)
                    && method.methodType().equalsString(descriptor))
        .findFirst();
  }

  private static String packageOf(String binaryName) {
    int dot = binaryName.lastIndexOf('.');
    return dot < 0 ? "" : binaryName.substring(0, dot);
  }
}
