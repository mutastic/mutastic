package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// [io.mutastic.api.BuiltIn#CONSTANT_VALUE]: a number in a method's code, and what it can become.
///
/// In the bytecode a `boolean` and a `char` are `int`s, so what uses the number decides what it's
/// allowed to be. `0` and `1` are changed only with evidence they're numbers, as otherwise they may
/// well be `false` and `true`. Any other `int` that isn't shown to be plain stays in the ranges of
/// `byte`, `short` and `char` it's in, as a mutant can't write what the source couldn't: `-1` as a
/// `char` has no meaning, and what it did would look like a catch.
final class Constants {
  private static final String PREFIX = "constant ";

  private static final List<long[]> NARROW_RANGES =
      List.of(
          new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE},
          new long[] {Short.MIN_VALUE, Short.MAX_VALUE},
          new long[] {Character.MIN_VALUE, Character.MAX_VALUE});

  private final List<CodeElement> code;
  private final Stores stores;
  private final MethodModel method;

  Constants(List<CodeElement> code, Stores stores, MethodModel method) {
    this.code = code;
    this.stores = stores;
    this.method = method;
  }

  /// What the number at `code[at]` can become, and why not to run those, if it's one to change.
  List<Mutator.Change> changes(int at) {
    if (!(code.get(at) instanceof ConstantInstruction constant)
        || !(constant.constantValue() instanceof Number value)
        || isJavacsOwn(at)) {
      return List.of();
    }
    var skipped = aridReason(value, at);
    return values(value, at).stream()
        .map(to -> new Mutator.Change(PREFIX + value + " to " + to, skipped))
        .toList();
  }

  private List<Number> values(Number value, int at) {
    return switch (value) {
      case Integer number -> wholeNumbers(number, at);
      case Long number -> longs(number);
      case Float number -> floats(number);
      default -> doubles(value.doubleValue());
    };
  }

  private List<Number> wholeNumbers(int value, int at) {
    boolean plain = isPlainNumber(at);
    if ((value == 0 || value == 1) && !plain) {
      return List.of();
    }
    var values = new ArrayList<Number>();
    for (long to : nearby(value, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
      if (plain || inTheSameRanges(value, to)) {
        values.add((int) to);
      }
    }
    return values;
  }

  private static List<Number> longs(long value) {
    return List.copyOf(nearby(value, Long.MIN_VALUE, Long.MAX_VALUE));
  }

  /// One either side, as far as the type goes, then `0`, `1` and `-1`; each once, and not itself.
  private static Set<Long> nearby(long value, long least, long most) {
    var values = new LinkedHashSet<Long>();
    if (value < most) {
      values.add(value + 1);
    }
    if (value > least) {
      values.add(value - 1);
    }
    values.addAll(List.of(0L, 1L, -1L));
    values.remove(value);
    return values;
  }

  private static List<Number> floats(float value) {
    var values = new LinkedHashSet<Number>(List.of(value + 1, value - 1, 0f, 1f, -1f));
    values.remove(value);
    return List.copyOf(values);
  }

  private static List<Number> doubles(double value) {
    var values = new LinkedHashSet<Number>(List.of(value + 1, value - 1, 0d, 1d, -1d));
    values.remove(value);
    return List.copyOf(values);
  }

  private static boolean inTheSameRanges(long value, long to) {
    return NARROW_RANGES.stream()
        .filter(range -> range[0] <= value && value <= range[1])
        .allMatch(range -> range[0] <= to && to <= range[1]);
  }

  /// A number no source wrote: the index or size in an array's `{…}`, the `-1` in `~x`, and what
  /// javac stores in a temporary of its own, an enhanced `for`'s index or a `String` switch's.
  private boolean isJavacsOwn(int at) {
    if (isAnArrayIndex(at)) {
      return true;
    }
    var next = SiteSkips.instructionsAfter(code, at, 2);
    if (next.isEmpty()) {
      return false;
    }
    var first = next.getFirst();
    return first.opcode() == Opcode.IXOR
        || first.opcode() == Opcode.LXOR
        || (isNewArray(first) && next.size() == 2 && next.getLast().opcode() == Opcode.DUP)
        || (first instanceof StoreInstruction store && variableStoredBy(store, at).isEmpty());
  }

  /// `dup; 0; …; aastore`, unless what's duplicated is a new object: `new; dup; 16; <init>`.
  private boolean isAnArrayIndex(int at) {
    var before = ImpliedOr.instructionsBefore(code, at, 2);
    return !before.isEmpty()
        && before.getLast().opcode() == Opcode.DUP
        && (before.size() == 1 || before.getFirst().opcode() != Opcode.NEW);
  }

  private Optional<String> variableStoredBy(StoreInstruction store, int at) {
    return stores
        .storedVariable(indexOfNextInstruction(at), store.slot())
        .map(local -> local.type().stringValue());
  }

  private int indexOfNextInstruction(int at) {
    int index = at + 1;
    while (index < code.size() && !(code.get(index) instanceof Instruction)) {
      index++;
    }
    return index;
  }

  /// Why changing the number isn't worth a test: an array's size, a variable's first `0` or `1`
  /// (where a counter or a total starts, whose change shows at once or says little), or a `split`'s
  /// limit, which rarely shows.
  private Optional<String> aridReason(Number value, int at) {
    var next = SiteSkips.instructionsAfter(code, at, 1);
    if (next.isEmpty()) {
      return Optional.empty();
    }
    var first = next.getFirst();
    if (isNewArray(first)) {
      return Optional.of("arid: only sizes an array");
    }
    if (first instanceof StoreInstruction store && startsAt0Or1(value, store, at)) {
      return Optional.of("arid: a variable's first value of 0 or 1");
    }
    if (first instanceof InvokeInstruction call && isSplit(call)) {
      return Optional.of("arid: only limits a split");
    }
    return Optional.empty();
  }

  private boolean startsAt0Or1(Number value, StoreInstruction store, int at) {
    double number = value.doubleValue();
    return (number == 0 || number == 1)
        && stores.variableIn(indexOfNextInstruction(at), store.slot()).isEmpty();
  }

  private static boolean isSplit(InvokeInstruction call) {
    return call.owner().asInternalName().equals("java/lang/String")
        && call.name().equalsString("split")
        && call.typeSymbol().parameterCount() == 2;
  }

  private static boolean isNewArray(Instruction instruction) {
    return switch (instruction.opcode()) {
      case NEWARRAY, ANEWARRAY, MULTIANEWARRAY -> true;
      default -> false;
    };
  }

  /// Whether what uses the `int` right after it shows it's a plain number, not a `boolean` or
  /// `char`.
  private boolean isPlainNumber(int at) {
    var next = SiteSkips.instructionsAfter(code, at, 1);
    if (next.isEmpty()) {
      return false;
    }
    return switch (next.getFirst()) {
      case OperatorInstruction operator -> !isBitwise(operator.opcode());
      case ConvertInstruction _ -> true;
      case BranchInstruction branch -> comparesNumbers(branch, at);
      case InvokeInstruction call -> takesAnIntLast(call);
      case StoreInstruction store ->
          variableStoredBy(store, at).filter(type -> type.equals("I")).isPresent();
      case FieldInstruction field -> field.type().equalsString("I");
      case ReturnInstruction _ ->
          method.methodTypeSymbol().returnType().descriptorString().equals("I");
      case ArrayStoreInstruction store -> store.opcode() == Opcode.IASTORE;
      default -> false;
    };
  }

  private static boolean isBitwise(Opcode opcode) {
    return opcode == Opcode.IAND || opcode == Opcode.IOR || opcode == Opcode.IXOR;
  }

  private static boolean takesAnIntLast(InvokeInstruction call) {
    var parameters = call.typeSymbol().parameterList();
    return !parameters.isEmpty() && parameters.getLast().descriptorString().equals("I");
  }

  /// An ordered comparison is of numbers. `==` and `!=` could be of `boolean`s, `flag == true`, so
  /// what's compared has to say.
  private boolean comparesNumbers(BranchInstruction branch, int at) {
    return switch (branch.opcode()) {
      case IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> true;
      case IF_ICMPEQ, IF_ICMPNE -> comparedWithAnInt(at);
      default -> false;
    };
  }

  private boolean comparedWithAnInt(int at) {
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    if (before.isEmpty()) {
      return false;
    }
    return switch (before.getFirst()) {
      case InvokeInstruction call -> call.typeSymbol().returnType().descriptorString().equals("I");
      case FieldInstruction field -> field.type().equalsString("I");
      case LoadInstruction load ->
          stores
              .variableIn(at, load.slot())
              .filter(local -> local.type().equalsString("I"))
              .isPresent();
      default -> false;
    };
  }

  /// The number in its place: the label is `constant 100 to 101`.
  static void write(CodeBuilder builder, ConstantInstruction constant, Mutant mutant) {
    var label = mutant.replacement();
    var to = label.substring(label.lastIndexOf(' ') + 1);
    switch (constant.constantValue()) {
      case Integer _ -> builder.loadConstant(Integer.parseInt(to));
      case Long _ -> builder.loadConstant(Long.parseLong(to));
      case Float _ -> builder.loadConstant(Float.parseFloat(to));
      default -> builder.loadConstant(Double.parseDouble(to));
    }
  }
}
