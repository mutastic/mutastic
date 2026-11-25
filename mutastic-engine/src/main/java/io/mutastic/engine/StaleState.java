package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/// The test classes whose run can see state an earlier run left in a test-source class: a catch by
/// one of them is run again in a fresh loader before it counts, as one run of the build starts
/// every static clean. A test class shared by every run of a worker (one a framework's scan finds,
/// one the JDK makes for a service) keeps its statics from run to run, so a test that counts its
/// runs, or remembers what the code said, can fail against a mutant it never fails against in the
/// build.
///
/// A test-source class keeps such state when it writes a static field of its own outside its
/// initialiser, has a static field that isn't final, or keeps in a static final one something that
/// can change: an array, a `java.util` object, a plain `Object`, or one of the test sources' own
/// classes with an instance field that can. A test class reaches it when it, or any test-source
/// class it refers to, directly or through others, keeps it. A nested class and the class it's
/// nested in name each other, so each reaches what the other keeps.
final class StaleState {
  private final Set<String> reaching;

  private StaleState(Set<String> reaching) {
    this.reaching = Set.copyOf(reaching);
  }

  /// From the class files under `roots`, the project's test classes.
  static StaleState in(List<Path> roots) {
    var classes = new HashMap<String, ClassModel>();
    for (var root : roots) {
      classFiles(root).forEach(file -> read(file, classes));
    }
    return of(classes);
  }

  /// From the test sources' classes, by internal name.
  static StaleState of(Map<String, ClassModel> classes) {
    var holders = new Holders(classes);
    var keeping = new HashSet<String>();
    classes.forEach(
        (name, model) -> {
          if (keepsState(model, holders)) {
            keeping.add(name);
          }
        });
    return new StaleState(reachingAny(classes, keeping));
  }

  /// Whether a run of `test` (`com.example.FooTest#bar()`, or a class) can see state an earlier run
  /// left.
  boolean reaches(String test) {
    int hash = test.indexOf('#');
    var className = hash < 0 ? test : test.substring(0, hash);
    return reaching.contains(className.replace('.', '/'));
  }

  private static List<Path> classFiles(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(file -> file.toString().endsWith(".class")).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void read(Path file, Map<String, ClassModel> classes) {
    try {
      var model = ClassFile.of().parse(Files.readAllBytes(file));
      classes.put(model.thisClass().asInternalName(), model);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (IllegalArgumentException notAClass) {
      // A file named .class that isn't one, or module-info of a newer JDK: no state to keep.
    }
  }

  private static boolean keepsState(ClassModel model, Holders holders) {
    var self = model.thisClass().asInternalName();
    return model.fields().stream().anyMatch(field -> isStateful(field, holders))
        || writesItsStaticsLater(model, self);
  }

  /// A static field that can change, or that holds something that can.
  private static boolean isStateful(FieldModel field, Holders holders) {
    if (!field.flags().has(AccessFlag.STATIC) || isNotTheTests(field)) {
      return false;
    }
    return !field.flags().has(AccessFlag.FINAL)
        || holders.canChange(field.fieldType().stringValue());
  }

  /// Fields javac or a coverage agent adds, and a class's serial version: never a test's state.
  private static boolean isNotTheTests(FieldModel field) {
    var name = field.fieldName().stringValue();
    return field.flags().has(AccessFlag.SYNTHETIC)
        || name.equals("serialVersionUID")
        || name.equals("$assertionsDisabled")
        || name.startsWith("$jacoco");
  }

  /// A method but the initialiser that writes one of the class's own static fields.
  private static boolean writesItsStaticsLater(ClassModel model, String self) {
    return model.methods().stream()
        .filter(method -> !method.methodName().stringValue().equals("<clinit>"))
        .flatMap(method -> method.code().stream())
        .flatMap(code -> code.elementStream())
        .anyMatch(
            element ->
                element instanceof FieldInstruction instruction
                    && instruction.opcode() == Opcode.PUTSTATIC
                    && instruction.owner().asInternalName().equals(self));
  }

  /// Every class that keeps state, or refers, directly or through others, to one that does.
  private static Set<String> reachingAny(Map<String, ClassModel> classes, Set<String> keeping) {
    var references = new HashMap<String, Set<String>>();
    classes.forEach((name, model) -> references.put(name, referencesOf(model)));
    var reached = new HashSet<>(keeping);
    for (boolean grew = true; grew; ) {
      grew = false;
      for (var entry : references.entrySet()) {
        if (!reached.contains(entry.getKey())
            && entry.getValue().stream().anyMatch(reached::contains)) {
          grew = reached.add(entry.getKey()) || grew;
        }
      }
    }
    return reached;
  }

  /// The classes `model` names: its superclass, interfaces and every class its code uses.
  private static Set<String> referencesOf(ClassModel model) {
    var names = new HashSet<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof ClassEntry classEntry) {
        names.add(classEntry.asInternalName());
      }
    }
    return names;
  }

  /// Which field types hold something that can change: the test sources' own classes are judged by
  /// their instance fields, once each, a class that refers back to itself holding nothing more.
  private static final class Holders {
    private final Map<String, ClassModel> classes;
    private final Map<String, Boolean> judged = new HashMap<>();

    Holders(Map<String, ClassModel> classes) {
      this.classes = classes;
    }

    boolean canChange(String descriptor) {
      if (descriptor.startsWith("[")
          || descriptor.startsWith("Ljava/util/")
          || descriptor.equals("Ljava/lang/Object;")
          || descriptor.equals("Ljava/lang/StringBuilder;")
          || descriptor.equals("Ljava/lang/ThreadLocal;")) {
        return true;
      }
      if (!descriptor.startsWith("L")) {
        return false;
      }
      return testSourceClassCanChange(descriptor.substring(1, descriptor.length() - 1));
    }

    private boolean testSourceClassCanChange(String internalName) {
      var model = classes.get(internalName);
      if (model == null) {
        return false;
      }
      var known = judged.get(internalName);
      if (known != null) {
        return known;
      }
      judged.put(internalName, false);
      boolean changes =
          model.fields().stream()
              .filter(field -> !field.flags().has(AccessFlag.STATIC))
              .anyMatch(
                  field ->
                      !field.flags().has(AccessFlag.FINAL)
                          || canChange(field.fieldType().stringValue()));
      judged.put(internalName, changes);
      return changes;
    }
  }
}
