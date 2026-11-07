package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Project;
import io.mutastic.api.Skip;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// An inline function's own body, where nothing runs it. kotlinc copies the function into each call
/// and the JVM never calls it, so "no test reaches this" would be false of code the tests do run:
/// what they run is a copy, and the mutants on a copy in the module's code are the ones that count.
///
/// kotlinc marks the function with a local of its own, `$i$f$twice`, and the callers' maps name its
/// lines. Java can call one like any other method, so one that's called stays as it is, as does one
/// with no copies anywhere, as then it is true that nothing reaches it.
final class InlineBodies implements Filter {
  private final List<ClassModel> main = new ArrayList<>();
  private final List<ClassModel> tests = new ArrayList<>();
  private boolean testsRead;

  @Override
  public String id() {
    return "mutastic:kotlin-inline-bodies";
  }

  @Override
  public void prepare(List<ClassModel> module) {
    main.addAll(module);
  }

  /// Says where the project's tests are, as a call in a test is copied into the test's class.
  void prepareFor(Project project) {
    if (!testsRead && main.stream().anyMatch(InlineBodies::hasAnInlineFunction)) {
      testsRead = true;
      project.testClasses().forEach(root -> tests.addAll(classesIn(root)));
    }
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    if (!isInlineBody(owner, method) || isCalled(owner, method)) {
      return Optional.empty();
    }
    var lines = ownLines(owner, method);
    if (holdACopy(main, owner, lines)) {
      return generated("the mutants on its copies are the ones that count");
    }
    return holdACopy(tests, owner, lines)
        ? generated("only tests call it, and what's copied into a test isn't changed")
        : Optional.empty();
  }

  private static Optional<Skip> generated(String why) {
    return Optional.of(
        new Skip(
            Skip.Kind.GENERATED,
            "kotlinc copies an inline function into each call, and nothing runs this body: "
                + why));
  }

  /// Whether kotlinc marked the method as an inline function, with a local holding its own name.
  static boolean isInlineBody(ClassModel owner, MethodModel method) {
    var marker = "$i$f$" + method.methodName().stringValue();
    return KotlinGenerated.isKotlin(owner)
        && method
            .code()
            .flatMap(code -> code.findAttribute(Attributes.localVariableTable()))
            .stream()
            .flatMap(table -> table.localVariables().stream())
            .anyMatch(variable -> variable.name().equalsString(marker));
  }

  private static boolean hasAnInlineFunction(ClassModel model) {
    return model.methods().stream().anyMatch(method -> isInlineBody(model, method));
  }

  private boolean isCalled(ClassModel owner, MethodModel method) {
    return Stream.concat(main.stream(), tests.stream())
        .flatMap(model -> model.methods().stream())
        .flatMap(caller -> caller.code().stream())
        .flatMap(code -> code.elementStream())
        .anyMatch(
            element -> element instanceof InvokeInstruction call && calls(call, owner, method));
  }

  private static boolean calls(InvokeInstruction call, ClassModel owner, MethodModel method) {
    return call.name().equals(method.methodName())
        && call.type().equals(method.methodType())
        && call.owner().asInternalName().equals(owner.thisClass().asInternalName());
  }

  /// The method's lines in its own file, the ones any copy of it is a copy of.
  private static Set<Integer> ownLines(ClassModel owner, MethodModel method) {
    var map = SourceMap.of(owner);
    var lines = new HashSet<Integer>();
    method
        .code()
        .ifPresent(
            code ->
                code.forEach(
                    element -> {
                      if (element instanceof LineNumber number
                          && map.copied(number.line()).isEmpty()) {
                        lines.add(number.line());
                      }
                    }));
    return lines;
  }

  private static boolean holdACopy(List<ClassModel> models, ClassModel owner, Set<Integer> lines) {
    var name = owner.thisClass().asInternalName();
    return models.stream()
        .map(SourceMap::of)
        .anyMatch(map -> lines.stream().anyMatch(line -> map.holdsCopyOf(name, line)));
  }

  private static List<ClassModel> classesIn(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(file -> file.toString().endsWith(".class"))
          .map(InlineBodies::parsed)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ClassModel parsed(Path file) {
    try {
      return ClassFile.of().parse(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
