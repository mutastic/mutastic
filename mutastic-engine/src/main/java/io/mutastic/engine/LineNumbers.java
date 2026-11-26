package io.mutastic.engine;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.LineNumber;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeSet;

/// Whether the classes with mutants say where each one is. Coverage is recorded by line, so a
/// method compiled without line numbers (`-g:none`, Maven's `<debug>false</debug>`) records nothing
/// and every mutant in it would read as reached by no test: a score of nothing measured. Counting
/// the tests that enter the method instead would say a test reaches a mutant on a branch it never
/// takes, so the run refuses, naming the classes.
///
/// Only a method with mutants that aren't skipped counts: what kotlinc writes with no line is
/// skipped as generated ([KotlinGenerated]), and a change to a class as a whole has no site in a
/// method.
final class LineNumbers {
  /// The most classes a refusal names; it says how many more there are.
  private static final int NAMED = 5;

  private LineNumbers() {}

  /// Refuses `mutants` when any of them is in a method without line numbers.
  static void require(List<Mutant> mutants, Map<String, ClassModel> models) {
    var lacking = lacking(mutants, models);
    if (!lacking.isEmpty()) {
      throw new Engine.NoLineNumbers(said(List.copyOf(lacking)));
    }
  }

  /// The binary names of the classes with a mutant in a method without line numbers, in order.
  static SequencedSet<String> lacking(List<Mutant> mutants, Map<String, ClassModel> models) {
    var lacking = new TreeSet<String>();
    for (Mutant mutant : mutants) {
      var model = models.get(mutant.binaryName());
      if (mutant.skipped().isEmpty()
          && !(mutant.operator() instanceof ClassMutator)
          && model != null
          && withoutLines(model, mutant)) {
        lacking.add(mutant.binaryName());
      }
    }
    return lacking;
  }

  private static boolean withoutLines(ClassModel model, Mutant mutant) {
    return model.methods().stream()
        .filter(method -> isMethodOf(method, mutant))
        .flatMap(method -> method.code().stream())
        .anyMatch(code -> code.elementStream().noneMatch(LineNumber.class::isInstance));
  }

  private static boolean isMethodOf(MethodModel method, Mutant mutant) {
    return method.methodName().equalsString(mutant.methodName())
        && method.methodType().equalsString(mutant.descriptor());
  }

  /// `com.example.Fees was compiled without line numbers, …`, naming at most [#NAMED] classes.
  private static String said(List<String> classes) {
    var named = classes.subList(0, Math.min(NAMED, classes.size()));
    var more = classes.size() - named.size();
    var list =
        more == 0 ? joined(named) : String.join(", ", named) + " and %d more".formatted(more);
    boolean one = classes.size() == 1;
    return ("%s %s compiled without line numbers, so mutastic can't tell which tests reach %s"
            + " mutants, and every one would read as reached by no test. Compile %s with line"
            + " numbers, as javac does by default: take -g:none out of the compiler's arguments"
            + " (Gradle's options.compilerArgs, Maven's <compilerArgs>), and leave Gradle's"
            + " options.debugOptions.debugLevel unset and Maven's <debug> at true.")
        .formatted(list, one ? "was" : "were", one ? "its" : "their", one ? "it" : "them");
  }

  /// `A`, `A and B`, `A, B and C`.
  private static String joined(List<String> names) {
    if (names.size() == 1) {
      return names.getFirst();
    }
    return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }
}
