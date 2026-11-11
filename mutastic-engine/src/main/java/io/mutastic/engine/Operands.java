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
  private record Local(
      int slot, String name, int from, int to, boolean predicate, boolean character) {}

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
  /// `character` when its type is `char`; `code` when it's an `int` constant, which a `char` beside
  /// it makes a character too (javac writes `'/'` as `47`).
  private record Operand(
      String text,
      int start,
      boolean predicate,
      int precedence,
      boolean character,
      @Nullable Integer code) {
    /// A value with no operator of its own: a load, a constant, a call.
    Operand(String text, int start, boolean predicate) {
      this(text, start, predicate, ATOM, false, null);
    }

    /// A value with no operator of its own, of type `char` when `character`.
    static Operand of(String text, int start, boolean predicate, boolean character) {
      return new Operand(text, start, predicate, ATOM, character, null);
    }

    /// The constant as a character, `'/'`, when it's an `int` one.
    Operand asCharacter() {
      return code == null || code < 0 || code > Character.MAX_VALUE
          ? this
          : of(characterLiteral(code), start, false, true);
    }
  }

  /// `String`'s methods whose first `int` is a character: `indexOf('/')`.
  private static final Set<String> CODE_POINTS_IN_TEXT = Set.of("indexOf", "lastIndexOf");

  /// `Character`'s methods whose first `int` isn't a character: `forDigit(digit, radix)`.
  private static final Set<String> NOT_CODE_POINTS = Set.of("forDigit");

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
                        variable.type().equalsString("Z"),
                        variable.type().equalsString("C")));
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
    var found = lastOperands(before, wanted);
    if (found.size() < wanted) {
      return List.of();
    }
    var operands = found.size() == 2 ? alike(found.get(0), found.get(1)) : found;
    var names = new ArrayList<String>(operands.stream().map(Operand::text).toList());
    if (wanted == 1 && !found.getFirst().predicate()) {
      names.add(isNullCheck(instruction) ? "null" : "0");
    }
    return names;
  }

  /// The last `wanted` operands of `before`, in order; fewer if they can't all be named. Only the
  /// first may have no known start.
  private List<Operand> lastOperands(List<Instruction> before, int wanted) {
    var found = new ArrayList<Operand>();
    int end = before.size();
    for (int i = 0; i < wanted; i++) {
      var operand = endingAt(before, end);
      if (operand == null || (operand.start() < 0 && i < wanted - 1)) {
        return List.of();
      }
      found.addFirst(operand);
      end = operand.start();
    }
    return found;
  }

  /// `left` and `right`, each a character when the other is: `c >= 'a'`, not `c >= 97`.
  private static List<Operand> alike(Operand left, Operand right) {
    return List.of(
        right.character() ? left.asCharacter() : left,
        left.character() ? right.asCharacter() : right);
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
      case ConstantInstruction constant -> constant(constant, end - 1);
      // `price * quantity` with a long and an int: javac widens `quantity` first. Still `quantity`.
      case ConvertInstruction _ -> endingAt(before, end - 1);
      case FieldInstruction field when field.opcode() == Opcode.GETFIELD ->
          field(before, end, field);
      case FieldInstruction field when field.opcode() == Opcode.GETSTATIC ->
          staticField(field, end - 1);
      // `start <= day` with an `Integer start`: javac unboxes with `intValue()`. Still `start`.
      case InvokeInstruction call when isUnboxing(call) -> unboxed(before, end, call);
      case InvokeInstruction call -> call(before, end, call);
      case OperatorInstruction operation -> operated(before, end, operation);
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

  /// `b + 1` or `items.length`; none for an operation the source can't be read back from.
  private @Nullable Operand operated(
      List<Instruction> before, int end, OperatorInstruction operation) {
    var arithmetic = arithmetic(operation.opcode());
    if (arithmetic != null) {
      return operation(before, end, arithmetic);
    }
    return operation.opcode() == Opcode.ARRAYLENGTH ? arrayLength(before, end) : null;
  }

  /// `items.length`: an array's length, as the source writes it.
  private @Nullable Operand arrayLength(List<Instruction> before, int end) {
    var array = endingAt(before, end - 1);
    if (array == null || array.start() < 0) {
      return null;
    }
    return new Operand(array.text() + ".length", array.start(), false);
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
    var type = field.typeSymbol().descriptorString();
    return Operand.of(
        own ? name : owner.text() + "." + name, owner.start(), type.equals("Z"), type.equals("C"));
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
    var sides = alike(left, right);
    var text =
        "%s %s %s"
            .formatted(
                bracketed(sides.get(0), left.precedence() < precedence),
                arithmetic.symbol(),
                bracketed(sides.get(1), right.precedence() <= precedence));
    return new Operand(text, left.start(), false, precedence, false, null);
  }

  private static String bracketed(Operand operand, boolean needed) {
    return needed ? "(" + operand.text() + ")" : operand.text();
  }

  private @Nullable Operand unboxed(List<Instruction> before, int end, InvokeInstruction call) {
    var boxed = endingAt(before, end - 1);
    return boxed == null
        ? null
        : Operand.of(
            boxed.text(),
            boxed.start(),
            call.name().equalsString("booleanValue"),
            call.name().equalsString("charValue"));
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
    var returns = type.returnType().descriptorString();
    boolean predicate = returns.equals("Z");
    boolean character = returns.equals("C");
    var name = call.name().stringValue();
    var arguments = arguments(before, end, call);
    if (arguments == null) {
      return Operand.of(name + "(…)", -1, predicate, character);
    }
    int at = arguments.start();
    var list = "(" + String.join(", ", arguments.texts()) + ")";
    if (call.opcode() == Opcode.INVOKESTATIC) {
      return Operand.of(owned(call.owner()) + name + list, at, predicate, character);
    }
    var receiver = endingAt(before, at);
    // A local with no name is javac's own, like a for-each loop's iterator: leave it out.
    return receiver == null || receiver.start() < 0 || receiver.text().startsWith("local")
        ? Operand.of(name + list, -1, predicate, character)
        : Operand.of(receiver.text() + "." + name + list, receiver.start(), predicate, character);
  }

  /// A call's arguments as the source writes them, and where the first starts.
  private record Arguments(List<String> texts, int start) {}

  /// The arguments of `call`, which ends at `end`, each a character where the call takes one; null
  /// if one can't be named.
  private @Nullable Arguments arguments(List<Instruction> before, int end, InvokeInstruction call) {
    var texts = new ArrayList<String>();
    int at = end - 1;
    for (int i = call.typeSymbol().parameterCount() - 1; i >= 0; i--) {
      var argument = endingAt(before, at);
      if (argument == null || argument.start() < 0) {
        return null;
      }
      if (!argument.text().isEmpty()) {
        texts.addFirst(takesACharacter(call, i) ? argument.asCharacter().text() : argument.text());
      }
      at = argument.start();
    }
    return new Arguments(texts, at);
  }

  /// The source name for a local, by where it's read: javac reuses a slot for variables in
  /// different scopes, so the name depends on the position.
  private Operand local(LoadInstruction load, int start) {
    int at = positions.getOrDefault(load, -1);
    return locals.stream()
        .filter(local -> local.slot() == load.slot() && local.from() <= at && at < local.to())
        .findFirst()
        .map(local -> Operand.of(local.name(), start, local.predicate(), local.character()))
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
    var type = field.typeSymbol().descriptorString();
    return Operand.of(
        owned(field.owner()) + field.name().stringValue(),
        start,
        type.equals("Z"),
        type.equals("C"));
  }

  /// An `int` constant keeps its value as `code`, in case a `char` beside it makes it one.
  private static Operand constant(ConstantInstruction constant, int start) {
    var value = constant.constantValue();
    return new Operand(
        literal(value), start, false, ATOM, false, value instanceof Integer code ? code : null);
  }

  /// Whether the call's parameter at `index` is a character: a `char`, or the `int` code point
  /// `String.indexOf('/')` and `Character.isDigit(c)` take.
  private static boolean takesACharacter(InvokeInstruction call, int index) {
    var type = call.typeSymbol();
    if (type.parameterType(index).descriptorString().equals("C")) {
      return true;
    }
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    return index == 0
        && type.parameterType(0).descriptorString().equals("I")
        && ((owner.equals("java/lang/String") && CODE_POINTS_IN_TEXT.contains(name))
            || ((owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/StringBuffer"))
                && name.equals("appendCodePoint"))
            || (owner.equals("java/lang/Character") && !NOT_CODE_POINTS.contains(name)));
  }

  /// `'/'`, `'\n'`, `'\''`; a character not written as itself as its escape.
  static String characterLiteral(int code) {
    return switch (code) {
      case 0 -> "'\\0'";
      case '\n' -> "'\\n'";
      case '\t' -> "'\\t'";
      case '\r' -> "'\\r'";
      case '\'' -> "'\\''";
      case '\\' -> "'\\\\'";
      default -> code >= ' ' && code <= '~' ? "'" + (char) code + "'" : "'\\u%04x'".formatted(code);
    };
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
