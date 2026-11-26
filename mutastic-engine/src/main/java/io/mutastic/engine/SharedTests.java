package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// The test classes the worker runs by their own names, where it would otherwise run a renamed copy
/// each run: those a copy would break, as the class files show.
///
/// The worker runs a top-level test class in a package production code also uses as a renamed copy
/// each run (`BinderTest__m2`), so its statics, and what a library keeps of it (a Spring test
/// context, built anew for a new class), start clean every run. A copy breaks a test two ways:
///
/// - a test class outside those packages refers to it by its own name, which the copy doesn't have,
///   so the two don't meet (A4, as in jcommander: a holder class in another package with a field of
///   a test class's type);
/// - it reads its own name, as a string in its class file holds it, and the copy's name is another
///   (A4, as in kotlinpoet: generated code that names its test classes).
///
/// Each such class, with the classes nested in it, runs by its own name, and so does every test
/// class it refers to that would be a copy, since it finds them by their own names too. All the
/// others stay copies. A class run by its own name keeps its statics across runs, so a catch by a
/// test that can see them runs again in a fresh loader before it counts ([StaleState]).
final class SharedTests {
  private SharedTests() {}

  /// The top-level test classes in `testRoots` that run by their own names, by binary name
  /// (`com.example.BinderTest`), sorted; `productionRoots` say which packages production code uses.
  static Set<String> in(List<Path> productionRoots, List<Path> testRoots) {
    var packages = new HashSet<String>();
    productionRoots.forEach(
        root -> classFiles(root).forEach(file -> packages.add(packageOf(root, file))));
    var tests = new HashMap<String, ClassModel>();
    testRoots.forEach(root -> classFiles(root).forEach(file -> read(file, tests)));
    return of(packages, tests);
  }

  /// The same, from the packages production code uses (`com/example`) and the test classes by
  /// internal name.
  static Set<String> of(Set<String> packages, Map<String, ClassModel> tests) {
    var copied = new HashSet<String>();
    tests.keySet().stream()
        .map(SharedTests::topLevel)
        .filter(top -> packages.contains(packageOf(top)))
        .forEach(copied::add);
    var shared = new HashSet<String>();
    tests.forEach((name, model) -> addWhatACopyBreaks(name, model, copied, shared));
    closeOver(shared, copied, tests);
    var names = new TreeSet<String>();
    shared.forEach(name -> names.add(name.replace('/', '.')));
    return names;
  }

  /// Adds to `shared` the copied classes `model` shows a copy would break: those it refers to, if
  /// it's no copy itself; those whose names its strings hold; and the class literals whose names it
  /// reads.
  private static void addWhatACopyBreaks(
      String name, ClassModel model, Set<String> copied, Set<String> shared) {
    if (!copied.contains(topLevel(name))) {
      referencesOf(model).stream()
          .map(SharedTests::topLevel)
          .filter(copied::contains)
          .forEach(shared::add);
    }
    namesInItsText(model, copied).forEach(shared::add);
    literalsWhoseNamesAreRead(model).stream()
        .map(SharedTests::topLevel)
        .filter(copied::contains)
        .forEach(shared::add);
  }

  /// Adds each copied class one already shared refers to, until none is left to add.
  private static void closeOver(
      Set<String> shared, Set<String> copied, Map<String, ClassModel> tests) {
    for (boolean grew = true; grew; ) {
      grew = false;
      for (var entry : tests.entrySet()) {
        if (shared.contains(topLevel(entry.getKey()))) {
          for (var referred : referencesOf(entry.getValue())) {
            var top = topLevel(referred);
            if (copied.contains(top) && shared.add(top)) {
              grew = true;
            }
          }
        }
      }
    }
  }

  /// The copied classes whose names a string in `model` holds, as code that prints or compares a
  /// class's name does: `demo.BinderTest`, `demo.BinderTest$Nested`, `demo.BinderTest.Nested`, or
  /// generated code naming another test class
  /// (`@com.squareup.kotlinpoet.TypeAliasAnnotation(...)`).
  private static Set<String> namesInItsText(ClassModel model, Set<String> copied) {
    var named = new HashSet<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof StringEntry string) {
        var dotted = DOTTED.matcher(string.stringValue());
        while (dotted.find()) {
          var parts = dotted.group("name").split("[.$]", -1);
          var prefix = new StringBuilder(parts[0]);
          for (int at = 1; at < parts.length; at++) {
            prefix.append('/').append(parts[at]);
            if (copied.contains(prefix.toString())) {
              named.add(prefix.toString());
            }
          }
        }
      }
    }
    return named;
  }

  /// `com.example.BinderTest$Nested`: a name with dots in it, as a string may hold one.
  private static final Pattern DOTTED = Pattern.compile("(?<name>[\\w$]+(?:\\.[\\w$]+)+)");

  /// The classes `model` names, by internal name: in its constant pool's class entries, and in the
  /// type descriptors its other text holds, as an annotation's class value (`converter =
  /// HostPortConverter.class`), a field's type or a generic signature do.
  private static Set<String> referencesOf(ClassModel model) {
    var names = new HashSet<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof ClassEntry classEntry && !classEntry.asInternalName().startsWith("[")) {
        names.add(classEntry.asInternalName());
      } else if (entry instanceof Utf8Entry text) {
        var named = DESCRIBED.matcher(text.stringValue());
        while (named.find()) {
          names.add(named.group("name"));
        }
      }
    }
    return names;
  }

  /// `Lcom/example/HostPort;`, a class as a descriptor names it.
  private static final Pattern DESCRIBED = Pattern.compile("L(?<name>[\\w/$]+);");

  /// The methods of `Class` that give a class's name.
  private static final Set<String> NAME_METHODS =
      Set.of("getName", "getCanonicalName", "getSimpleName", "getTypeName");

  /// The class literals `model`'s code loads (`Foo.class`), if it reads a class's name anywhere
  /// (`getCanonicalName()`): a copy of one would be found by a name the code doesn't expect.
  private static Set<String> literalsWhoseNamesAreRead(ClassModel model) {
    var literals = new HashSet<String>();
    boolean readsNames = false;
    for (var method : model.methods()) {
      for (var element : method.code().map(code -> code.elementList()).orElse(List.of())) {
        if (element instanceof ConstantInstruction.LoadConstantInstruction load
            && load.constantEntry() instanceof ClassEntry literal) {
          literals.add(literal.asInternalName());
        } else if (element instanceof InvokeInstruction call
            && call.owner().asInternalName().equals("java/lang/Class")
            && NAME_METHODS.contains(call.name().stringValue())) {
          readsNames = true;
        }
      }
    }
    return readsNames ? literals : Set.of();
  }

  /// `com/example/BinderTest$Nested` → `com/example/BinderTest`.
  private static String topLevel(String internalName) {
    int dollar = internalName.indexOf('$', internalName.lastIndexOf('/') + 1);
    return dollar < 0 ? internalName : internalName.substring(0, dollar);
  }

  private static String packageOf(String internalName) {
    int slash = internalName.lastIndexOf('/');
    return slash < 0 ? "" : internalName.substring(0, slash);
  }

  private static String packageOf(Path root, Path file) {
    var relative = root.relativize(file.getParent() == null ? root : file.getParent()).toString();
    return relative.replace(root.getFileSystem().getSeparator(), "/");
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

  private static void read(Path file, Map<String, ClassModel> tests) {
    try {
      var model = ClassFile.of().parse(Files.readAllBytes(file));
      tests.put(model.thisClass().asInternalName(), model);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (IllegalArgumentException notAClass) {
      // A file named .class that isn't one, or module-info of a newer JDK: it names nothing.
    }
  }
}
