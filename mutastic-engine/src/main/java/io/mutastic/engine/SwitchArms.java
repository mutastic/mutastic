package io.mutastic.engine;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DynamicConstantDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// What `mutastic/remove-switch-arm` makes of a switch: each entry in turn goes to the default.
///
/// Its label is `arm 2 2`: `arm`, the value in the bytecode (which it needs to find the entry), and
/// how the source says it, if the bytecode shows that: a number, `"a"` for a `String`, `Circle` for
/// a pattern. An `enum`'s values are javac's own numbering, kept in another class, so it has no
/// name, and the card goes by the arm's line. With `throwing ` in front, the default is javac's
/// own, which throws: the switch was exhaustive, so a value that reached the arm now throws.
///
/// javac's own parts have none: a `String` switch's first switch, which only hashes, and an entry
/// that goes to the default already, as the gaps in a `tableswitch` do.
final class SwitchArms {
  private static final String ARM = "arm ";
  private static final String THROWING = "throwing ";

  /// javac's defaults for a switch that needs none: what it throws when a value reaches it anyway.
  private static final Set<String> SYNTHETIC_DEFAULTS =
      Set.of("java/lang/MatchException", "java/lang/IncompatibleClassChangeError");

  private SwitchArms() {}

  /// One arm: its label, the line it starts on, and why it's skipped if it is.
  record Arm(String label, int line, Optional<String> skipped) {}

  private record Entries(Label otherwise, List<SwitchCase> cases) {
    static Optional<Entries> of(CodeElement element) {
      return switch (element) {
        case TableSwitchInstruction table ->
            Optional.of(new Entries(table.defaultTarget(), table.cases()));
        case LookupSwitchInstruction lookup ->
            Optional.of(new Entries(lookup.defaultTarget(), lookup.cases()));
        default -> Optional.empty();
      };
    }
  }

  /// The arms of the switch at `code[at]`, if it is a switch that has arms of the source's.
  static List<Arm> at(List<CodeElement> code, int at) {
    var found = Entries.of(code.get(at));
    if (found.isEmpty() || isHashLookup(code, at)) {
      return List.of();
    }
    var entries = found.get();
    var names = names(code, at);
    var prefix = throwsByDefault(code, entries.otherwise()) ? THROWING : "";
    var arms = new ArrayList<Arm>();
    for (var entry : entries.cases()) {
      if (!entry.target().equals(entries.otherwise())) {
        var name = names.apply(entry.caseValue());
        arms.add(
            new Arm(
                prefix + ARM + entry.caseValue() + name.map(said -> " " + said).orElse(""),
                lineOf(code, entry.target()),
                doesNothing(code, entry.target(), entries.otherwise())
                    ? Optional.of("equivalent: the arm goes straight to where the default goes")
                    : Optional.empty()));
      }
    }
    return arms;
  }

  /// How the source says a value, as far as the bytecode shows.
  private interface Names {
    Optional<String> apply(int value);
  }

  private static Names names(List<CodeElement> code, int at) {
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    var last = before.isEmpty() ? null : before.getLast();
    return switch (last) {
      case InvokeDynamicInstruction pattern when isPatternSwitch(pattern) ->
          value -> patternName(pattern, value);
      case LoadInstruction load when !stringsBefore(code, at, load.slot()).isEmpty() -> {
        var strings = stringsBefore(code, at, load.slot());
        yield value -> Optional.ofNullable(strings.get(value));
      }
      case Instruction numbered when isEnumNumbering(numbered) -> _ -> Optional.empty();
      case null, default -> value -> Optional.of(String.valueOf(value));
    };
  }

  /// `$SwitchMap$…[e.ordinal()]`, or for an `enum` of the same source file `e.ordinal()` alone: a
  /// number that isn't how the source says the constant.
  private static boolean isEnumNumbering(Instruction instruction) {
    return instruction.opcode() == Opcode.IALOAD
        || (instruction instanceof InvokeInstruction call && call.name().equalsString("ordinal"));
  }

  private static boolean isPatternSwitch(InvokeDynamicInstruction call) {
    return call.bootstrapMethod()
        .owner()
        .descriptorString()
        .equals("Ljava/lang/runtime/SwitchBootstraps;");
  }

  /// A pattern switch's value is where its label is among the bootstrap's arguments: a type, a
  /// constant or an `enum`'s constant; `-1` is `case null`.
  private static Optional<String> patternName(InvokeDynamicInstruction call, int value) {
    if (value == -1) {
      return Optional.of("null");
    }
    var labels = call.bootstrapArgs();
    if (value < 0 || value >= labels.size()) {
      return Optional.empty();
    }
    return said(labels.get(value), call.name().equalsString("enumSwitch"));
  }

  private static Optional<String> said(ConstantDesc label, boolean ofAnEnum) {
    return switch (label) {
      case ClassDesc type -> {
        var name = type.displayName();
        yield Optional.of(name.substring(name.lastIndexOf('$') + 1));
      }
      case String text -> Optional.of(ofAnEnum ? text : "\"" + text + "\"");
      case Integer number -> Optional.of(number.toString());
      case DynamicConstantDesc<?> constant -> enumConstant(constant);
      default -> Optional.empty();
    };
  }

  /// `case Level.LOW` on a sealed type: javac writes a constant that a bootstrap makes with
  /// `EnumDesc.of(type, "LOW")`, so its own name is the bootstrap's, `invoke`, and the constant's
  /// is the text among its arguments.
  private static Optional<String> enumConstant(DynamicConstantDesc<?> constant) {
    return java.util.Arrays.stream(constant.bootstrapArgs())
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .reduce((_, last) -> last);
  }

  /// A `String` switch's second switch goes on what its first stored: `ldc "a"; equals; ifeq;
  /// iconst 0; istore slot`. Each number and the text that stores it, back as far as that first
  /// switch.
  private static Map<Integer, String> stringsBefore(List<CodeElement> code, int at, int slot) {
    var strings = new HashMap<Integer, String>();
    for (int index = at - 1; index >= 0 && !isHashLookup(code, index); index--) {
      if (code.get(index) instanceof ConstantInstruction constant
          && constant.constantValue() instanceof String text) {
        storedAfter(code, index, slot).ifPresent(value -> strings.put(value, "\"" + text + "\""));
      }
    }
    return strings;
  }

  /// The number stored in `slot` when the text at `code[index]` is what's being switched on.
  private static Optional<Integer> storedAfter(List<CodeElement> code, int index, int slot) {
    var next = SiteSkips.instructionsAfter(code, index, 4);
    return next.size() == 4
            && isCall(next.get(0), "java/lang/String", "equals")
            && next.get(1) instanceof BranchInstruction
            && next.get(2) instanceof ConstantInstruction number
            && number.constantValue() instanceof Integer value
            && next.get(3) instanceof StoreInstruction store
            && store.slot() == slot
        ? Optional.of(value)
        : Optional.empty();
  }

  /// javac's first switch on a `String`: on its hash, each arm going on to test it's that text. A
  /// switch of the project's own on a `hashCode()` has arms of its own.
  static boolean isHashLookup(List<CodeElement> code, int at) {
    var entries = Entries.of(code.get(at));
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    if (entries.isEmpty()
        || entries.get().cases().isEmpty()
        || before.isEmpty()
        || !isCall(before.getLast(), "java/lang/String", "hashCode")) {
      return false;
    }
    var arm =
        SiteSkips.instructionsAfter(
            code, position(code, entries.get().cases().getFirst().target()), 3);
    return arm.size() == 3
        && arm.get(1) instanceof ConstantInstruction constant
        && constant.constantValue() instanceof String
        && isCall(arm.get(2), "java/lang/String", "equals");
  }

  private static boolean isCall(Instruction instruction, String owner, String name) {
    return instruction instanceof InvokeInstruction call
        && call.owner().asInternalName().equals(owner)
        && call.name().equalsString(name);
  }

  /// Whether the default is javac's own: `new MatchException`, and so on, then a throw.
  private static boolean throwsByDefault(List<CodeElement> code, Label otherwise) {
    var first = SiteSkips.instructionsAfter(code, position(code, otherwise), 1);
    return !first.isEmpty()
        && first.getFirst() instanceof NewObjectInstruction created
        && SYNTHETIC_DEFAULTS.contains(created.className().asInternalName());
  }

  /// `case 1: break;`, with no code of its own: a jump to where the default goes anyway.
  private static boolean doesNothing(List<CodeElement> code, Label target, Label otherwise) {
    var first = SiteSkips.instructionsAfter(code, position(code, target), 1);
    return !first.isEmpty()
        && first.getFirst() instanceof BranchInstruction jump
        && jump.opcode() == Opcode.GOTO
        && jump.target().equals(otherwise);
  }

  /// The line the code at `target` is on: the arm's own, so its card shows the arm, and only tests
  /// that reach it run.
  private static int lineOf(List<CodeElement> code, Label target) {
    int line = -1;
    boolean there = false;
    for (var element : code) {
      switch (element) {
        case LineNumber number -> line = number.line();
        case LabelTarget label -> there |= label.label().equals(target);
        case Instruction _ -> {
          if (there) {
            return line;
          }
        }
        default -> {}
      }
    }
    return line;
  }

  private static int position(List<CodeElement> code, Label label) {
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof LabelTarget target && target.label().equals(label)) {
        return at;
      }
    }
    throw new IllegalStateException("no such label in the method");
  }

  /// The switch, with the entry the label names going to the default.
  static void write(CodeBuilder builder, Instruction instruction, String label) {
    var words = label.substring(label.indexOf(ARM) + ARM.length()).split(" ", 2);
    int value = Integer.parseInt(words[0]);
    switch (instruction) {
      case TableSwitchInstruction table ->
          builder.with(
              TableSwitchInstruction.of(
                  table.lowValue(),
                  table.highValue(),
                  table.defaultTarget(),
                  retargeted(table.cases(), value, table.defaultTarget())));
      case LookupSwitchInstruction lookup ->
          builder.with(
              LookupSwitchInstruction.of(
                  lookup.defaultTarget(),
                  retargeted(lookup.cases(), value, lookup.defaultTarget())));
      default -> throw new IllegalStateException("not a switch: " + instruction);
    }
  }

  private static List<SwitchCase> retargeted(List<SwitchCase> cases, int value, Label otherwise) {
    return cases.stream()
        .map(each -> each.caseValue() == value ? SwitchCase.of(value, otherwise) : each)
        .toList();
  }
}
