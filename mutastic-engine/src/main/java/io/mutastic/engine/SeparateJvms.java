package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/// The project's `main` classes that a test runs in a JVM of its own: a test class that starts a
/// process, and names the class, by its `.class` or its name. Mutants are swapped into the worker's
/// JVM, and coverage is measured there, so neither reaches that JVM: its code reads as reached by
/// no test, or a mutant there as missed, though the test runs it and may well check it. The run
/// says so for each one found ([#notes]).
///
/// The evidence is in the bytecode: the call that starts the process, and the class named. A test
/// that starts `git` names no `main` class, so it's never one.
final class SeparateJvms {
  /// The calls that start a process.
  private static final Set<String> STARTS =
      Set.of(
          "java/lang/ProcessBuilder.start",
          "java/lang/ProcessBuilder.startPipeline",
          "java/lang/Runtime.exec");

  private SeparateJvms() {}

  /// What a test class names that runs in a JVM of its own: `MainTest runs app.Main`.
  record Run(String test, String main) {
    String note() {
      var simple = main.substring(main.lastIndexOf('.') + 1);
      return ("%s runs %s in a separate JVM, and mutants don't reach that JVM. So a test there"
              + " can't catch a mutant in %s, and code only it runs reads as reached by no"
              + " test. To check %s, call what its main calls from a test in the tests' own"
              + " JVM")
          .formatted(test.substring(test.lastIndexOf('.') + 1), main, simple, simple);
    }
  }

  /// Each test class under `testRoots` that names one of `models` that has a `main`, and starts a
  /// process or names a test class that does, in order.
  static List<Run> in(List<Path> testRoots, Map<String, ClassModel> models) {
    var mains = new TreeSet<String>();
    models.forEach(
        (name, model) -> {
          if (hasMain(model)) {
            mains.add(name);
          }
        });
    if (mains.isEmpty()) {
      return List.of();
    }
    var tests = new ArrayList<ClassModel>();
    classFiles(testRoots).forEach(file -> tests.add(ClassFile.of().parse(read(file))));
    return runs(tests, mains);
  }

  private static List<Run> runs(List<ClassModel> tests, Set<String> mains) {
    var starters = new TreeSet<String>();
    tests.stream().filter(SeparateJvms::startsAProcess).forEach(test -> starters.add(name(test)));
    var runs = new ArrayList<Run>();
    for (var test : tests) {
      var names = named(test);
      if (starters.contains(name(test)) || names.stream().anyMatch(starters::contains)) {
        names.stream()
            .filter(mains::contains)
            .forEach(main -> runs.add(new Run(outer(name(test)), main)));
      }
    }
    return runs.stream().distinct().toList();
  }

  private static String name(ClassModel model) {
    return model.thisClass().asInternalName().replace('/', '.');
  }

  /// The notes the run gives: one for each test class and `main` class.
  static List<String> notes(Collection<Run> runs) {
    return runs.stream().map(Run::note).toList();
  }

  private static boolean hasMain(ClassModel model) {
    return model.methods().stream()
        .anyMatch(
            method ->
                method.methodName().equalsString("main")
                    && method.methodType().equalsString("([Ljava/lang/String;)V")
                    && method.flags().has(AccessFlag.STATIC));
  }

  private static boolean startsAProcess(ClassModel test) {
    for (var entry : test.constantPool()) {
      if (entry instanceof MethodRefEntry call
          && STARTS.contains(call.owner().asInternalName() + "." + call.name().stringValue())) {
        return true;
      }
    }
    return false;
  }

  /// The classes a test class names: as a class, `Main.class`, or by name, `"app.Main"`.
  private static Set<String> named(ClassModel test) {
    var found = new TreeSet<String>();
    for (var entry : test.constantPool()) {
      switch (entry) {
        case ClassEntry type -> found.add(type.asInternalName().replace('/', '.'));
        case StringEntry text -> found.add(text.stringValue());
        default -> {}
      }
    }
    return found;
  }

  /// A nested class's test is its outer class's, as the run names tests.
  private static String outer(String test) {
    int nested = test.indexOf('$');
    return nested < 0 ? test : test.substring(0, nested);
  }

  private static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Path> classFiles(List<Path> roots) {
    var files = new ArrayList<Path>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(file -> file.toString().endsWith(".class")).sorted().forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }
}
