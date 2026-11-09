package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// What a comparison or calculation works on, as the source names it: `total` and `100` in `total
/// >= 100`, `element.length()` and `2` in `element.length() < 2`, read back from the instructions
/// just before it. A branch on a `boolean` call gives one operand, the call:
/// `Files.isRegularFile(git)`. Empty when the code is more involved than that.
///
/// Fed every instruction in order, and told when a jump target is passed: what came before a jump
/// target isn't the only way in, so it doesn't count.
final class Operands {
  private record Local(int slot, String name, int from, int to, boolean predicate) {}

  /// `this$0`: the outer instance, in an inner class.
  private static final java.util.regex.Pattern OUTER =
      java.util.regex.Pattern.compile("this\\$\\d+");

  private static final Set<String> BOXES =
      Set.of(
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte",
          "java/lang/Character",
          "java/lang/Boolean",
          "java/lang/Float",
          "java/lang/Double");

  /// The primitive each box's `TYPE` is the class of.
  private static final java.util.Map<String, String> PRIMITIVES =
      java.util.Map.of(
          "java/lang/Integer", "int",
          "java/lang/Long", "long",
          "java/lang/Short", "short",
          "java/lang/Byte", "byte",
          "java/lang/Character", "char",
          "java/lang/Boolean", "boolean",
          "java/lang/Float", "float",
          "java/lang/Double", "double",
          "java/lang/Void", "void");

  /// One value on the stack and where its instructions start; `start` is -1 when unknown. Its
  /// precedence is the operator's, for an operation, so an operation around it knows to bracket it.
  private record Operand(String text, int start, boolean predicate, int precedence) {
    /// A value with no operator of its own: a load, a constant, a call.
    Operand(String text, int start, boolean predicate) {
      this(text, start, predicate, ATOM);
    }
  }

  /// The precedence of a value that isn't an operation: above every operator's.
  private static final int ATOM = 100;

  /// The arithmetic the operands name, with Java's precedence: `*` binds tighter than `+`.
  private record Arithmetic(String symbol, int precedence) {}

  /// The arithmetic an operator instruction is, if it's one the operands name.
  private static @Nullable Arithmetic arithmetic(Opcode opcode) {
    return switch (opcode) {
      case IMUL, LMUL -> new Arithmetic("*", 12);
      case IDIV, LDIV -> new Arithmetic("/", 12);
      case IREM, LREM -> new Arithmetic("%", 12);
      case IADD, LADD -> new Arithmetic("+", 11);
      case ISUB, LSUB -> new Arithmetic("-", 11);
      default -> null;
    };
  }

  private final List<Local> locals = new ArrayList<>();
  private final IdentityHashMap<Instruction, Integer> positions = new IdentityHashMap<>();
  private final List<Instruction> recent = new ArrayList<>();
  private int position;

  /// The class the code is in, as `com/example/Rates`: its own statics, the source names bare.
  private final String self;

  Operands(CodeModel code) {
    self =
        code.parent()
            .flatMap(java.lang.classfile.MethodModel::parent)
            .map(owner -> owner.thisClass().asInternalName())
            .orElse("");
    code.findAttribute(Attributes.localVariableTable())
        .ifPresent(
            table -> {
              for (var variable : table.localVariables()) {
                locals.add(
                    new Local(
                        variable.slot(),
                        variable.name().stringValue(),
                        variable.startPc(),
                        variable.startPc() + variable.length(),
                        variable.type().equalsString("Z")));
              }
            });
  }

  /// A jump lands here.
  void jumpTarget() {
    recent.clear();
  }

  /// What `instruction` works on, before adding it to what's been seen.
  List<String> of(Instruction instruction) {
    var operands = operands(instruction);
    positions.put(instruction, position);
    position += instruction.sizeInBytes();
    recent.add(instruction);
    return operands;
  }

  private List<String> operands(Instruction instruction) {
    var before = recent;
    // `from > 0` on longs is LCMP then IFLE: the operands are the two values before LCMP.
    boolean afterCompare = !before.isEmpty() && isCompare(before.getLast());
    if (afterCompare) {
      before = before.subList(0, before.size() - 1);
    }
    int wanted = operandCount(instruction, afterCompare);
    var names = new ArrayList<String>();
    int end = before.size();
    boolean predicate = false;
    for (int i = 0; i < wanted; i++) {
      var operand = endingAt(before, end);
      if (operand == null || (operand.start() < 0 && i < wanted - 1)) {
        return List.of();
      }
      names.addFirst(operand.text());
      end = operand.start();
      predicate = operand.predicate();
    }
    if (wanted == 1 && !predicate) {
      names.add(isNullCheck(instruction) ? "null" : "0");
    }
    return names;
  }

  /// How many values on the stack `instruction` works on, of the ones its operands can name.
  private static int operandCount(Instruction instruction, boolean afterCompare) {
    return switch (instruction) {
      case BranchInstruction branch when branch.opcode().name().startsWith("IF_ICMP") -> 2;
      case BranchInstruction _ when afterCompare -> 2;
      // `start == null`: the value, then `null` as a comparison with zero has `0`.
      case BranchInstruction branch when isNullCheck(branch) -> 1;
      // `operator != BuiltIn.BOUNDARY`: two references, compared as objects.
      case BranchInstruction branch when branch.opcode().name().startsWith("IF_ACMP") -> 2;
      case BranchInstruction branch when branch.opcode() == Opcode.GOTO -> 0;
      case BranchInstruction _ -> 1; // compares with zero
      case OperatorInstruction _ -> 2;
      default -> 0;
    };
  }

  private @Nullable Operand endingAt(List<Instruction> before, int end) {
    if (end <= 0) {
      return null;
    }
    return switch (before.get(end - 1)) {
      case LoadInstruction load -> local(load, end - 1);
      case ConstantInstruction constant ->
          new Operand(literal(constant.constantValue()), end - 1, false);
      // `price * quantity` with a long and an int: javac widens `quantity` first. Still `quantity`.
      case ConvertInstruction _ -> endingAt(before, end - 1);
      case FieldInstruction field when field.opcode() == Opcode.GETFIELD ->
          field(before, end, field);
      case FieldInstruction field when field.opcode() == Opcode.GETSTATIC ->
          staticField(field, end - 1);
      // `start <= day` with an `Integer start`: javac unboxes with `intValue()`. Still `start`.
      case InvokeInstruction call when isUnboxing(call) -> unboxed(before, end, call);
      case InvokeInstruction call -> call(before, end, call);
      case OperatorInstruction operation when arithmetic(operation.opcode()) != null ->
          operation(before, end, java.util.Objects.requireNonNull(arithmetic(operation.opcode())));
      // An empty varargs array (`Files.isRegularFile(git)` passes `new LinkOption[0]`): no
      // argument.
      case NewReferenceArrayInstruction _
          when end >= 2
              && before.get(end - 2) instanceof ConstantInstruction size
              && Integer.valueOf(0).equals(size.constantValue()) ->
          new Operand("", end - 2, false);
      default -> null;
    };
  }

  /// `end` for a field of `this`, as the source usually writes it; `window.end` for another's.
  private @Nullable Operand field(List<Instruction> before, int end, FieldInstruction field) {
    var owner = endingAt(before, end - 1);
    if (owner == null || owner.start() < 0) {
      return null;
    }
    var name = field.name().stringValue();
    // `this$0` is the outer instance javac gives an inner class: the source names its fields bare.
    boolean own = owner.text().equals("this") || OUTER.matcher(owner.text()).matches();
    return new Operand(
        own ? name : owner.text() + "." + name,
        owner.start(),
        field.typeSymbol().descriptorString().equals("Z"));
  }

  /// `b + 1`, `(a + b) * 2`: an operation on two values the operands can name, bracketed where
  /// Java's precedence needs it. The right of a `-` or `/` is bracketed at the same precedence too,
  /// since `a - (b - c)` isn't `a - b - c`.
  private @Nullable Operand operation(List<Instruction> before, int end, Arithmetic arithmetic) {
    var right = endingAt(before, end - 1);
    if (right == null || right.start() < 0) {
      return null;
    }
    var left = endingAt(before, right.start());
    if (left == null || left.start() < 0) {
      return null;
    }
    int precedence = arithmetic.precedence();
    var text =
        "%s %s %s"
            .formatted(
                bracketed(left, left.precedence() < precedence),
                arithmetic.symbol(),
                bracketed(right, right.precedence() <= precedence));
    return new Operand(text, left.start(), false, precedence);
  }

  private static String bracketed(Operand operand, boolean needed) {
    return needed ? "(" + operand.text() + ")" : operand.text();
  }

  private @Nullable Operand unboxed(List<Instruction> before, int end, InvokeInstruction call) {
    var boxed = endingAt(before, end - 1);
    return boxed == null
        ? null
        : new Operand(boxed.text(), boxed.start(), call.name().equalsString("booleanValue"));
  }

  /// `Integer.intValue()` and the like: a call javac adds, not one the source makes.
  private static boolean isUnboxing(InvokeInstruction call) {
    return call.opcode() == Opcode.INVOKEVIRTUAL
        && BOXES.contains(call.owner().asInternalName())
        && call.name().stringValue().endsWith("Value")
        && call.typeSymbol().parameterCount() == 0;
  }

  /// `Files.isRegularFile(git)`, `head.startsWith("ref: ")`, or `startsWith(…)` when its arguments
  /// or receiver are more than a load or a constant.
  private Operand call(List<Instruction> before, int end, InvokeInstruction call) {
    var type = call.typeSymbol();
    boolean predicate = type.returnType().descriptorString().equals("Z");
    var name = call.name().stringValue();
    var arguments = new ArrayList<String>();
    int at = end - 1;
    for (int i = 0; i < type.parameterCount(); i++) {
      var argument = endingAt(before, at);
      if (argument == null || argument.start() < 0) {
        return new Operand(name + "(…)", -1, predicate);
      }
      if (!argument.text().isEmpty()) {
        arguments.addFirst(argument.text());
      }
      at = argument.start();
    }
    var list = "(" + String.join(", ", arguments) + ")";
    if (call.opcode() == Opcode.INVOKESTATIC) {
      return new Operand(owned(call.owner()) + name + list, at, predicate);
    }
    var receiver = endingAt(before, at);
    // A local with no name is javac's own, like a for-each loop's iterator: leave it out.
    return receiver == null || receiver.start() < 0 || receiver.text().startsWith("local")
        ? new Operand(name + list, -1, predicate)
        : new Operand(receiver.text() + "." + name + list, receiver.start(), predicate);
  }

  /// The source name for a local, by where it's read: javac reuses a slot for variables in
  /// different scopes, so the name depends on the position.
  private Operand local(LoadInstruction load, int start) {
    int at = positions.getOrDefault(load, -1);
    return locals.stream()
        .filter(local -> local.slot() == load.slot() && local.from() <= at && at < local.to())
        .findFirst()
        .map(local -> new Operand(local.name(), start, local.predicate()))
        .orElse(new Operand("local" + load.slot(), start, false));
  }

  /// A constant as the source writes it: `"{}"`, `100`, and a class as `KyberParameterSpec.class`,
  /// where its descriptor would say `ClassOrInterfaceDesc[KyberParameterSpec]`.
  private static String literal(Object value) {
    return switch (value) {
      case String text -> "\"" + text + "\"";
      case java.lang.constant.ClassDesc type -> type.displayName().replace('$', '.') + ".class";
      default -> String.valueOf(value);
    };
  }

  /// `BuiltIn.BOUNDARY`, `limit`: a static field, as a static call is named.
  private Operand staticField(FieldInstruction field, int start) {
    // `int.class` is `Integer.TYPE` to the bytecode.
    if (field.name().equalsString("TYPE")
        && field.typeSymbol().descriptorString().equals("Ljava/lang/Class;")
        && PRIMITIVES.containsKey(field.owner().asInternalName())) {
      return new Operand(PRIMITIVES.get(field.owner().asInternalName()) + ".class", start, false);
    }
    return new Operand(
        owned(field.owner()) + field.name().stringValue(),
        start,
        field.typeSymbol().descriptorString().equals("Z"));
  }

  /// `Rates.` before a static of another class; nothing before one of this class's own.
  private String owned(java.lang.classfile.constantpool.ClassEntry owner) {
    return owner.asInternalName().equals(self) ? "" : owner.asSymbol().displayName() + ".";
  }

  private static boolean isNullCheck(Instruction instruction) {
    return instruction.opcode() == Opcode.IFNULL || instruction.opcode() == Opcode.IFNONNULL;
  }

  private static boolean isCompare(Instruction instruction) {
    return switch (instruction.opcode()) {
      case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> true;
      default -> false;
    };
  }
}
