package io.mutastic.worker;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/// One run's test classes, in a fresh loader whose parent is the production loader, so test statics
/// (a port picked once in a static field) start clean every run, and tests see the swapped
/// production classes. Resources all come from the production loader, which has every directory in
/// Gradle's order, so none is found twice.
final class TestLoader extends RootsLoader {
  /// `[[Lcom.example.Parcel;`: an array as the JVM names it.
  private static final Pattern JVM_ARRAY =
      Pattern.compile("(?<dimensions>\\[+)L(?<element>[^;]+);");

  private final ProductionLoader production;
  private final int run;

  TestLoader(List<Path> roots, ProductionLoader production, int run) {
    super("mutastic-tests", roots, List.of(), production);
    this.production = production;
    this.run = run;
  }

  /// The class this run runs for the test class `name`: its renamed copy, if it's in a package
  /// production code also uses.
  String copyOf(String name) {
    var topLevel = production.splitTopLevel(name);
    return topLevel == null ? name : Renamer.renamed(name, topLevel, run);
  }

  /// `com.example.Parcel, int` → `com.example.Parcel__m2, int`: the types a test method takes, as
  /// this run's copy of its class declares them. Every reference a copy makes to a split test class
  /// is renamed, so one the method takes, as a parameterized test takes a record of its cases, is a
  /// copy too; and so is an array's element, named either way JUnit names one (`Parcel[]` or
  /// `[Lcom.example.Parcel;`).
  String parametersOfCopy(String parameters) {
    if (parameters.isEmpty()) {
      return parameters;
    }
    return Arrays.stream(parameters.split(",", -1))
        .map(String::strip)
        .map(this::typeOfCopy)
        .collect(Collectors.joining(", "));
  }

  private String typeOfCopy(String type) {
    var jvmArray = JVM_ARRAY.matcher(type);
    if (jvmArray.matches()) {
      return jvmArray.group("dimensions") + "L" + copyOf(jvmArray.group("element")) + ";";
    }
    int brackets = type.indexOf('[');
    return brackets <= 0
        ? copyOf(type)
        : copyOf(type.substring(0, brackets)) + type.substring(brackets);
  }

  /// A split test is the production loader's, by either name: a copy by its new name (TestNG looks
  /// a copy up that way), or the class as the build has it by its original name. `Class.forName`
  /// must get a class of the name it asked for, so the original name never gives a copy.
  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (production.splitTopLevel(Renamer.original(name)) != null || production.shared(name)) {
      return production.loadClass(name);
    }
    return super.loadClass(name, resolve);
  }

  @Override
  byte @Nullable [] bytesFor(String name) {
    return classFile(name);
  }
}
