package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Mutants at one place in the code that always do the same. Their ids were kept before mutastic
/// knew they're alike, and an id once kept names its mutant for good, so each is still made; but
/// only one of them runs, and each other is skipped as the same as it, so the place counts once in
/// the score.
///
/// Two ways are known. A for-each loop's test over an array has only two signs of the index against
/// the length, so two changes that jump alike for both are twins: `<` made `<=` and the test always
/// true, `<` made `>=` and `!=`, the test always false and `<` made `>`. And a comparison whose
/// answer is returned as it is (`return a > b;`, which javac compiles to `1` or `0` pushed on each
/// way and one `ireturn`): the test always true does what the return made `true` does, and always
/// false what `false` does.
///
/// The one that runs is the first in the defaults, then stronger, then extended, in the order the
/// mutants are found; one whose twins aren't in the run's operators runs as any mutant does.
final class Twins {
  private Twins() {}

  /// What a mutant at `code[at]`, by `operator` with the change `label`, does, as keys a twin of it
  /// shares: none when it isn't one of the shapes twins are known in.
  static Set<String> keys(List<CodeElement> code, int at, Operator operator, String label) {
    var keys = new LinkedHashSet<String>();
    if (operator == BuiltIn.REMOVE_CONDITIONAL || Comparisons.changesAComparison(operator)) {
      Comparisons.forEachJumps(code, at, label).ifPresent(jumps -> keys.add("loop@" + at + jumps));
    }
    returned(code, at, operator, label).ifPresent(keys::add);
    return keys;
  }

  /// Each mutant of a method that is the same as an earlier one of a set skipped as the same as it.
  /// Only a mutant that would run is one: a skip of another kind says more.
  ///
  /// @param keys what each mutant does, by id: from [#keys]
  static List<Mutant> marked(List<Mutant> mutants, Map<String, Set<String>> keys) {
    if (keys.isEmpty()) {
      return mutants;
    }
    var sets = sets(mutants, keys);
    var runs = new HashMap<String, String>();
    for (var set : sets) {
      var first =
          set.stream()
              .min(Comparator.comparing((Mutant mutant) -> mutant.operator().tier()))
              .orElseThrow();
      set.stream()
          .filter(mutant -> !mutant.id().equals(first.id()))
          .forEach(mutant -> runs.put(mutant.id(), first.id()));
    }
    var marked = new ArrayList<Mutant>(mutants.size());
    for (Mutant mutant : mutants) {
      var partner = runs.get(mutant.id());
      marked.add(partner == null ? mutant : mutant.sameAs(partner));
    }
    return marked;
  }

  /// The sets of twins, each in the order found: mutants that would run and share a key, or share
  /// one with a mutant that shares one with them.
  private static List<List<Mutant>> sets(List<Mutant> mutants, Map<String, Set<String>> keys) {
    var setOf = joined(byKey(mutants, keys));
    var sets = new ArrayList<List<Mutant>>();
    var seen = new java.util.HashSet<String>();
    for (Mutant mutant : mutants) {
      var set = setOf.get(mutant.id());
      if (set != null && set.size() > 1 && seen.add(mutant.id())) {
        set.forEach(each -> seen.add(each.id()));
        // In the order found, not the order joined.
        sets.add(mutants.stream().filter(set::contains).toList());
      }
    }
    return sets;
  }

  /// The mutants that would run, by each key they have.
  private static Map<String, List<Mutant>> byKey(
      List<Mutant> mutants, Map<String, Set<String>> keys) {
    var byKey = new LinkedHashMap<String, List<Mutant>>();
    for (Mutant mutant : mutants.stream().filter(mutant -> mutant.skipped().isEmpty()).toList()) {
      for (String key : keys.getOrDefault(mutant.id(), Set.of())) {
        byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mutant);
      }
    }
    return byKey;
  }

  /// Each mutant's set, by id: the mutants that share a key with it, joined with theirs.
  private static Map<String, SequencedSet<Mutant>> joined(Map<String, List<Mutant>> byKey) {
    var setOf = new HashMap<String, SequencedSet<Mutant>>();
    for (var sharing : byKey.values()) {
      var joined = new LinkedHashSet<Mutant>();
      for (Mutant mutant : sharing) {
        joined.addAll(setOf.getOrDefault(mutant.id(), new LinkedHashSet<>(List.of(mutant))));
      }
      joined.forEach(mutant -> setOf.put(mutant.id(), joined));
    }
    return setOf;
  }

  /// The value a mutant makes a returned comparison's method return, as a key: the test always true
  /// or false, or the return made that value.
  private static Optional<String> returned(
      List<CodeElement> code, int at, Operator operator, String label) {
    if (operator == BuiltIn.REMOVE_CONDITIONAL) {
      return ReturnedTest.from(code, at)
          .map(
              test ->
                  test.key(label.equals(Operators.NEVER_JUMP) ? test.fallsTo() : test.jumpsTo()));
    }
    if (operator == BuiltIn.RETURN_VALUE) {
      var value = returnedValue(label);
      if (value.isPresent()) {
        for (int test = at - 1; test >= 0; test--) {
          var found = ReturnedTest.from(code, test).filter(shape -> shape.returnAt() == at);
          if (found.isPresent()) {
            return Optional.of(found.get().key(value.getAsInt()));
          }
        }
      }
    }
    return Optional.empty();
  }

  /// The whole number `return true`, `return false`, `return 0` or `return 1` returns.
  private static OptionalInt returnedValue(String label) {
    return switch (label) {
      case "return true", "return 1" -> OptionalInt.of(1);
      case "return false", "return 0" -> OptionalInt.of(0);
      default -> OptionalInt.empty();
    };
  }

  /// A test whose answer is returned as it is: javac's `return a > b;`, the test at `code[at]`
  /// jumping to a `0` or `1`, falling to the other and on to the same `ireturn`, with no other way
  /// into either.
  ///
  /// ```
  /// if_icmple L; iconst_1; goto E
  /// L: iconst_0
  /// E: ireturn
  /// ```
  private record ReturnedTest(int fallsTo, int jumpsTo, int returnAt) {
    String key(int value) {
      return "return@" + returnAt + ":" + value;
    }

    static Optional<ReturnedTest> from(List<CodeElement> code, int at) {
      if (!(code.get(at) instanceof BranchInstruction test) || !Operators.isConditional(test)) {
        return Optional.empty();
      }
      var shape = Shape.after(code, at);
      if (!shape.returnsAConstant() || !shape.joins(test)) {
        return Optional.empty();
      }
      var into = targets(code);
      boolean onlyWays =
          shape.jumpedTo().stream().allMatch(label -> only(into, label, at))
              && shape.joined().stream().allMatch(label -> only(into, label, shape.exitAt()));
      return onlyWays
          ? Optional.of(
              new ReturnedTest(
                  shape.fallsTo().getAsInt(), shape.jumpsTo().getAsInt(), shape.returnAt()))
          : Optional.empty();
    }

    /// The code after a test, read as javac's returned test would be: a constant, a `goto`, the
    /// labels the test can jump to, the other constant, the labels the `goto` can go to, and an
    /// `ireturn`; each part empty or null where the code isn't that.
    private record Shape(
        OptionalInt fallsTo,
        int exitAt,
        @Nullable BranchInstruction exit,
        List<Label> jumpedTo,
        OptionalInt jumpsTo,
        List<Label> joined,
        int returnAt,
        @Nullable Instruction returns) {
      static Shape after(List<CodeElement> code, int at) {
        var walk = new Walk(code, at + 1);
        var fallsTo = walk.constant();
        int exitAt = walk.at();
        var exit =
            walk.instruction() instanceof BranchInstruction jump && jump.opcode() == Opcode.GOTO
                ? jump
                : null;
        var jumpedTo = walk.labels();
        var jumpsTo = walk.constant();
        var joined = walk.labels();
        int returnAt = walk.at();
        return new Shape(
            fallsTo, exitAt, exit, jumpedTo, jumpsTo, joined, returnAt, walk.instruction());
      }

      /// Whether each way pushes a different one of `0` and `1` and returns it.
      boolean returnsAConstant() {
        return fallsTo.isPresent()
            && jumpsTo.isPresent()
            && fallsTo.getAsInt() != jumpsTo.getAsInt()
            && returns != null
            && returns.opcode() == Opcode.IRETURN;
      }

      /// Whether `test` jumps to the second constant, and the `goto` after the first to the return.
      boolean joins(BranchInstruction test) {
        return exit != null && jumpedTo.contains(test.target()) && joined.contains(exit.target());
      }
    }

    /// Whether `label` is jumped to only from `code[from]`, or from nowhere.
    private static boolean only(Map<Label, List<Integer>> into, Label label, int from) {
      return into.getOrDefault(label, List.of()).stream().allMatch(source -> source == from);
    }

    /// Where in the code each label can be gone to from: jumps, switches and exception handlers.
    private static Map<Label, List<Integer>> targets(List<CodeElement> code) {
      var into = new HashMap<Label, List<Integer>>();
      for (int at = 0; at < code.size(); at++) {
        for (Label label : goesTo(code.get(at))) {
          into.computeIfAbsent(label, ignored -> new ArrayList<>()).add(at);
        }
      }
      return into;
    }

    private static List<Label> goesTo(CodeElement element) {
      return switch (element) {
        case BranchInstruction branch -> List.of(branch.target());
        case TableSwitchInstruction table ->
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(table.defaultTarget()),
                    table.cases().stream().map(each -> each.target()))
                .toList();
        case LookupSwitchInstruction lookup ->
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(lookup.defaultTarget()),
                    lookup.cases().stream().map(each -> each.target()))
                .toList();
        case ExceptionCatch handler -> List.of(handler.handler());
        default -> List.of();
      };
    }
  }

  /// The code from a place on, an instruction or a run of labels at a time, past the elements that
  /// aren't either (line numbers, the table of locals).
  private static final class Walk {
    private final List<CodeElement> code;
    private int at;

    Walk(List<CodeElement> code, int at) {
      this.code = code;
      this.at = at;
    }

    int at() {
      skip();
      return at;
    }

    /// The labels next, none when an instruction is.
    List<Label> labels() {
      var labels = new ArrayList<Label>();
      skip();
      while (at < code.size() && code.get(at) instanceof LabelTarget target) {
        labels.add(target.label());
        at++;
        skip();
      }
      return labels;
    }

    /// The instruction next, when one is.
    @Nullable Instruction instruction() {
      skip();
      if (at < code.size() && code.get(at) instanceof Instruction instruction) {
        at++;
        return instruction;
      }
      return null;
    }

    /// The `0` or `1` pushed next, when one is.
    OptionalInt constant() {
      skip();
      if (at < code.size() && code.get(at) instanceof Instruction instruction) {
        var value =
            switch (instruction.opcode()) {
              case ICONST_0 -> OptionalInt.of(0);
              case ICONST_1 -> OptionalInt.of(1);
              default -> OptionalInt.empty();
            };
        if (value.isPresent()) {
          at++;
        }
        return value;
      }
      return OptionalInt.empty();
    }

    private void skip() {
      while (at < code.size()
          && !(code.get(at) instanceof Instruction)
          && !(code.get(at) instanceof LabelTarget)) {
        at++;
      }
    }
  }
}
