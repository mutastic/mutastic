package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.Mutant;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.reflect.AccessFlag;
import java.util.Map;
import java.util.Optional;

/// Whether a test class could write the call a suggestion makes, and how. The worker calls methods
/// by reflection and can reach anything; a test can only reach what Java's access rules allow from
/// its package, and a suggested test that doesn't compile is worse than none.
final class Access {
  private Access() {}

  /// How code in `testClass` (a binary name) can call the mutant's method, if it can: `-` for a
  /// static method, else how to make the receiver. That's the descriptor of the reachable
  /// constructor with the fewest parameters, all of them whole numbers (`int`, `long`, `Integer`,
  /// `Long`), as a record of numbers has. An inner class is made inside an instance of its outer
  /// class, so its receiver is the outer class's, then `>`, then its own constructor, which takes
  /// that instance first: `()V>(Lcom/example/Shipping;I)V`.
  ///
  /// @param models the project's classes by binary name, the mutant's among them
  static Optional<String> receiver(
      Map<String, ClassModel> models, Mutant mutant, String testClass) {
    var model = requireNonNull(models.get(mutant.binaryName()), mutant.binaryName());
    // Kotlin's `private` on a class and `protected` are package-private and protected to the JVM,
    // but no other file can use them: only what's public is sure to compile.
    boolean samePackage =
        !KotlinGenerated.isKotlin(model)
            && packageOf(mutant.binaryName()).equals(packageOf(testClass));
    var method = method(model, mutant.methodName(), mutant.descriptor());
    if (method.isEmpty()
        || !reachable(method.get().flags().flags(), samePackage)
        || !classReachable(model, samePackage)) {
      return Optional.empty();
    }
    if (method.get().flags().has(AccessFlag.STATIC)) {
      return Optional.of("-");
    }
    return made(models, model, samePackage);
  }

  /// How to make an instance of `model`'s class: its constructor, after its outer class's if it's
  /// an inner class.
  private static Optional<String> made(
      Map<String, ClassModel> models, ClassModel model, boolean samePackage) {
    if (model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)) {
      return Optional.empty();
    }
    var outer = outerInstance(model);
    if (outer.isEmpty()) {
      return constructor(model, samePackage, 0);
    }
    var outerModel = models.get(outer.get());
    if (outerModel == null || !classReachable(outerModel, samePackage)) {
      return Optional.empty();
    }
    return made(models, outerModel, samePackage)
        .flatMap(outerMade -> constructor(model, samePackage, 1).map(own -> outerMade + ">" + own));
  }

  /// The reachable constructor with the fewest parameters after the first `skip` (an inner class's
  /// outer instance), all of them whole numbers: a no-argument one if there is one.
  private static Optional<String> constructor(ClassModel model, boolean samePackage, int skip) {
    return model.methods().stream()
        .filter(constructor -> constructor.methodName().equalsString("<init>"))
        .filter(constructor -> reachable(constructor.flags().flags(), samePackage))
        .filter(constructor -> constructor.methodTypeSymbol().parameterCount() >= skip)
        .filter(
            constructor ->
                constructor.methodTypeSymbol().parameterList().stream()
                    .skip(skip)
                    .allMatch(type -> WHOLE_NUMBERS.contains(type.descriptorString())))
        .min(
            java.util.Comparator.comparingInt(
                (MethodModel constructor) -> constructor.methodTypeSymbol().parameterCount()))
        .map(constructor -> constructor.methodType().stringValue());
  }

  /// Whether code in `testClass` can call the mutant's method at all.
  static boolean callableFrom(Map<String, ClassModel> models, Mutant mutant, String testClass) {
    return receiver(models, mutant, testClass).isPresent();
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
  /// javac keeps in the InnerClasses attribute (its own flags say public or package only). A test
  /// can name a static nested class or an inner one, but not an anonymous or local class.
  private static boolean classReachable(ClassModel model, boolean samePackage) {
    var declared = declared(model);
    if (declared.isPresent()) {
      boolean named =
          declared.get().flags().contains(AccessFlag.STATIC)
              || declared.get().outerClass().isPresent();
      return named && reachable(declared.get().flags(), samePackage);
    }
    return reachable(model.flags().flags(), samePackage);
  }

  /// The binary name of the class whose instance an inner class is made in, or empty for a class
  /// that needs none.
  private static Optional<String> outerInstance(ClassModel model) {
    return declared(model)
        .filter(declared -> !declared.flags().contains(AccessFlag.STATIC))
        .flatMap(InnerClassInfo::outerClass)
        .map(outer -> outer.asInternalName().replace('/', '.'));
  }

  /// How `model`'s class was declared, if it's nested in another.
  private static Optional<InnerClassInfo> declared(ClassModel model) {
    var self = model.thisClass().asInternalName();
    return model.findAttribute(Attributes.innerClasses()).stream()
        .flatMap(attribute -> attribute.classes().stream())
        .filter(inner -> inner.innerClass().asInternalName().equals(self))
        .findFirst();
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
