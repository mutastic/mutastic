package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.List;
import java.util.Optional;

/// Code no test reaches because only `main` runs it. A test can call `main`, but what `main` does
/// is hard to check from there: it reads the process's arguments, writes to `System.out`, and may
/// end the JVM. So the advice is the usual shape for it: what `main` does moves into a method a
/// test can call and check, and `main` calls it. `main` stays in the run, as its exit codes and
/// argument defaults matter.
final class OnlyFromMain {
  private OnlyFromMain() {}

  /// What to do about a place no test reaches, when it's in a `main` that does more than one line's
  /// work: one that already only calls the method that does it has nothing left to move.
  static Optional<String> advice(List<Mutant> place, SourceView sources) {
    boolean inMain = place.stream().allMatch(OnlyFromMain::isMain);
    long lines = place.stream().map(mutant -> sources.place(mutant).line()).distinct().count();
    return inMain && lines > 1
        ? Optional.of(
            "reached only from `main()`: move what it does into `run(args, out)`, which returns"
                + " the exit code, test `run`, and leave `main()` a one-line call to it")
        : Optional.empty();
  }

  /// Whether a mutant is in a class's `public static void main(String[] args)`, as the JVM starts
  /// it: the method's name and descriptor say it.
  static boolean isMain(Mutant mutant) {
    return mutant.methodName().equals("main")
        && mutant.descriptor().equals("([Ljava/lang/String;)V");
  }
}
