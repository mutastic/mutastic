package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// What a comparison's jump becomes, for [BuiltIn#BOUNDARY], [BuiltIn#COMPARISON] and
/// [BuiltIn#OTHER_COMPARISON].
///
/// For each comparison, three changes are enough: a test set that catches them catches every other
/// change of the comparison too (Kaminski, Ammann and Offutt on relational operator replacement).
/// For `a < b` they're `a <= b`, `a != b` and `false`; for `a == b`, `a <= b`, `a >= b` and
/// `false`; for `a != b`, `a < b`, `a > b` and `true`. Where one moves the edge (`<=` for `<`),
/// it's [BuiltIn#BOUNDARY]'s; the constant is [BuiltIn#REMOVE_CONDITIONAL]'s at the same jump; the
/// rest are `mutastic/comparison`'s. So no jump gets the same change twice.
///
/// The sets for a relation and for its opposite are each other's opposites, which is what makes
/// this work on bytecode: javac compiles `a < b` to a jump past the body when `a >= b`, or to one
/// into the body when `a < b` (the first test of an `||`), and either way the jump's own set, read
/// back through the inversion, is the source's. So a jump's changes don't depend on which way javac
/// compiled it.
///
/// Only a comparison of numbers has an order: `==` on two `boolean`s or references gets only its
/// opposite, in `mutastic/other-comparison`. A `float` or `double` is compared with `fcmpl` or
/// `fcmpg`, which differ only in what `NaN` gives; a change is made only where the jump still
/// treats `NaN` as the changed comparison does in Java (false, but true for `!=`), and, unless it
/// reverses the condition, gives the answer it gave there before: `x < y` becomes `x <= y`, not `x
/// != y`, which differs for `NaN` too.
final class Comparisons {
  /// What a jump tests: the sign of `a - b` (or of a value, against zero) it's taken for.
  enum Relation {
    LT,
    LE,
    GT,
    GE,
    EQ,
    NE;

    boolean holds(int sign) {
      return switch (this) {
        case LT -> sign < 0;
        case LE -> sign <= 0;
        case GT -> sign > 0;
        case GE -> sign >= 0;
        case EQ -> sign == 0;
        case NE -> sign != 0;
      };
    }

    Relation negated() {
      return switch (this) {
        case LT -> GE;
        case LE -> GT;
        case GT -> LE;
        case GE -> LT;
        case EQ -> NE;
        case NE -> EQ;
      };
    }

    /// The two of the three sufficient changes that are another comparison.
    List<Relation> sufficient() {
      return switch (this) {
        case LT -> List.of(LE, NE);
        case LE -> List.of(LT, EQ);
        case GT -> List.of(GE, NE);
        case GE -> List.of(GT, EQ);
        case EQ -> List.of(LE, GE);
        case NE -> List.of(LT, GT);
      };
    }

    /// `<` and `<=`, `>` and `>=`: the same comparison with its edge moved.
    boolean edgeOf(Relation other) {
      return switch (this) {
        case LT -> other == LE;
        case LE -> other == LT;
        case GT -> other == GE;
        case GE -> other == GT;
        default -> false;
      };
    }
  }

  /// `int` values a `boolean` can't be: `iand` and the like are left out, as `a & b` on two
  /// `boolean`s compiles to them too.
  private static final Set<Opcode> WHOLE_NUMBER_ARITHMETIC =
      Set.of(
          Opcode.IADD,
          Opcode.ISUB,
          Opcode.IMUL,
          Opcode.IDIV,
          Opcode.IREM,
          Opcode.INEG,
          Opcode.ISHL,
          Opcode.ISHR,
          Opcode.IUSHR,
          Opcode.ARRAYLENGTH);

  private static final Set<String> WHOLE_NUMBER_TYPES = Set.of("I", "S", "B", "C");

  /// The calls whose `int` the JDK documents as a length or count, by `owner.name`, with the only
  /// instruction that proves no other code answers them. A `java.` class is always the JDK's, as
  /// the JVM defines no other, but a call reaches whatever overrides it: so each is on a final
  /// class (`String`), or final itself (a buffer's `remaining()`, final in `Buffer`), or on an
  /// interface sealed to the JDK's own classes (`MethodTypeDesc`). A `java.util` collection's
  /// `size()` and a `CharSequence`'s `length()` are none of these, and a class of the project's can
  /// make either negative.
  private static final Map<String, Opcode> NEVER_NEGATIVE_CALLS = neverNegativeCalls();

  static final String NEVER_NEGATIVE =
      "equivalent: differs only when a length or count is negative. That can't happen because"
          + " it's an array's length or a JDK count no other class can override, and those start"
          + " at 0.";

  /// The calls whose `int` the JDK documents as -1 or an index, by `owner.name`, each on a final
  /// class or static, so no other code answers them: `String.indexOf` and the rest, whatever they
  /// search for, and `Character.digit`, -1 or a digit's value.
  private static final Map<String, Opcode> INDEX_CALLS = indexCalls();

  static final String NEVER_BELOW_MINUS_ONE =
      "equivalent: differs only when an index is below -1. That can't happen because it's from"
          + " the JDK's indexOf, lastIndexOf or Character.digit, which give -1 or an index.";

  private Comparisons() {}

  /// Whether `operator` is one of the three that change a comparison.
  static boolean changesAComparison(io.mutastic.api.Operator operator) {
    return operator == BuiltIn.BOUNDARY
        || operator == BuiltIn.COMPARISON
        || operator == BuiltIn.OTHER_COMPARISON;
  }

  /// The jump a comparison's mutant takes instead, by opcode name: `IF_ICMPLE`. None where the
  /// operator doesn't apply.
  static List<Mutator.Change> changes(BuiltIn operator, List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof BranchInstruction branch) || !Operators.isConditional(branch)) {
      return List.of();
    }
    var jump = Jump.of(branch.opcode());
    var before = ImpliedOr.instructionsBefore(code, at, 4);
    var site = new Site(jump, before, code);
    var reason = noLowerThanItsEdge(jump, before);
    boolean neverNegative = reason.isPresent();
    var changes = new ArrayList<Mutator.Change>();
    for (Relation change : relations(operator, site)) {
      var label = jump.family().opcode(change).name();
      if (neverNegative && sameFromZero(jump.relation(), change)) {
        changes.add(new Mutator.Change(label, reason));
      } else if (!neverNegative || madeHere(operator, site, change)) {
        changes.add(Mutator.Change.of(label));
      }
    }
    return changes;
  }

  /// For a value that's never negative, whether `operator` is the one to make `change`: not when,
  /// for zero and above, the changed jump always goes one way, as remove-conditional's mutants do,
  /// or goes as an earlier change's does (boundary's first, then comparison's, then the others), so
  /// no jump gets the same change twice. Which one makes it doesn't depend on the operators a run
  /// uses, so an id found by one run is found by any.
  private static boolean madeHere(BuiltIn operator, Site site, Relation change) {
    if (change.holds(0) == change.holds(1)) {
      return false; // remove-conditional's
    }
    for (BuiltIn earlier :
        List.of(BuiltIn.BOUNDARY, BuiltIn.COMPARISON, BuiltIn.OTHER_COMPARISON)) {
      for (Relation made : relations(earlier, site)) {
        if (earlier == operator && made == change) {
          return true;
        }
        boolean madeEarlier =
            !sameFromZero(site.jump().relation(), made) && made.holds(0) != made.holds(1);
        if (madeEarlier && sameFromZero(made, change)) {
          return false;
        }
      }
    }
    return true;
  }

  /// A comparison's jump, the instructions just before it, and its method's code.
  private record Site(Jump jump, List<Instruction> before, List<CodeElement> code) {}

  /// What the operator changes the jump's relation to.
  private static List<Relation> relations(BuiltIn operator, Site site) {
    var jump = site.jump();
    var before = site.before();
    boolean ordered = jump.family().numbers() && ofNumbers(jump, before, site.code());
    var relation = jump.relation();
    var sufficient = ordered ? relation.sufficient() : List.<Relation>of();
    List<Relation> wanted =
        switch (operator) {
          case BOUNDARY -> sufficient.stream().filter(relation::edgeOf).toList();
          case COMPARISON -> sufficient.stream().filter(other -> !relation.edgeOf(other)).toList();
          default -> ordered ? others(relation) : List.of(relation.negated());
        };
    var compare = before.isEmpty() ? null : before.getLast().opcode();
    return compare != null && nanOrdered(compare) ? keepingNan(compare, relation, wanted) : wanted;
  }

  /// Whether changing a comparison with zero, `jump`, to `replacement`, an opcode's name, changes
  /// what it does only for a value below zero: `IFLE` to `IFEQ`.
  static boolean onlyBelowZero(Opcode jump, String replacement) {
    var original = Jump.of(jump);
    if (original.family() != Family.WITH_ZERO) {
      return false;
    }
    return Arrays.stream(Relation.values())
        .filter(change -> Family.WITH_ZERO.opcode(change).name().equals(replacement))
        .anyMatch(
            change -> change != original.relation() && sameFromZero(original.relation(), change));
  }

  /// Whether the two relations give the same answer for zero and every value above it.
  private static boolean sameFromZero(Relation one, Relation other) {
    return one.holds(0) == other.holds(0) && one.holds(1) == other.holds(1);
  }

  /// Why what the jump compares can't be below where it's compared: a length or count against 0
  /// ([#NEVER_NEGATIVE]), or an index against -1 ([#NEVER_BELOW_MINUS_ONE]), `s.indexOf(c) == -1`,
  /// whose `a - b` is then zero or above. None for anything else, `-1 == s.indexOf(c)` among them.
  private static Optional<String> noLowerThanItsEdge(Jump jump, List<Instruction> before) {
    if (jump.family() == Family.WITH_ZERO && !before.isEmpty() && neverNegative(before.getLast())) {
      return Optional.of(NEVER_NEGATIVE);
    }
    boolean againstMinusOne =
        jump.family() == Family.TWO_INTS
            && before.size() >= 2
            && before.getLast().opcode() == Opcode.ICONST_M1
            && indexCall(before.get(before.size() - 2));
    return againstMinusOne ? Optional.of(NEVER_BELOW_MINUS_ONE) : Optional.empty();
  }

  /// A call in [#INDEX_CALLS], made with the instruction it's listed with, giving an `int`.
  private static boolean indexCall(Instruction instruction) {
    if (!(instruction instanceof InvokeInstruction call)
        || !call.typeSymbol().returnType().descriptorString().equals("I")) {
      return false;
    }
    var key = call.owner().asInternalName() + "." + call.name().stringValue();
    return call.opcode() == INDEX_CALLS.get(key);
  }

  /// `indexOf` and `lastIndexOf` on `String`, `StringBuilder` and `StringBuffer`, each final, and
  /// `Character.digit`, static. A `List`'s `indexOf` is an interface's, which a class of the
  /// project's can answer with anything.
  private static Map<String, Opcode> indexCalls() {
    var calls = new HashMap<String, Opcode>();
    for (var owner :
        List.of("java/lang/String", "java/lang/StringBuilder", "java/lang/StringBuffer")) {
      calls.put(owner + ".indexOf", Opcode.INVOKEVIRTUAL);
      calls.put(owner + ".lastIndexOf", Opcode.INVOKEVIRTUAL);
    }
    calls.put("java/lang/Character.digit", Opcode.INVOKESTATIC);
    return Map.copyOf(calls);
  }

  /// A value proved to be zero or above: an array's length, or a call in [#NEVER_NEGATIVE_CALLS]
  /// made with the instruction it's listed with.
  private static boolean neverNegative(Instruction instruction) {
    if (instruction.opcode() == Opcode.ARRAYLENGTH) {
      return true;
    }
    if (!(instruction instanceof InvokeInstruction call)
        || !call.typeSymbol().descriptorString().equals("()I")) {
      return false;
    }
    var key = call.owner().asInternalName() + "." + call.name().stringValue();
    return call.opcode() == NEVER_NEGATIVE_CALLS.get(key);
  }

  /// `String.length()`, `Matcher.groupCount()` and the rest, each on a final class; a buffer's
  /// `remaining()` and `capacity()`, final in `Buffer`; and `MethodTypeDesc.parameterCount()`, on
  /// an interface sealed to the JDK's one implementation.
  private static Map<String, Opcode> neverNegativeCalls() {
    var calls = new HashMap<String, Opcode>();
    for (var owner :
        List.of(
            "java/lang/String",
            "java/lang/StringBuilder",
            "java/lang/StringBuffer",
            "java/util/StringJoiner")) {
      calls.put(owner + ".length", Opcode.INVOKEVIRTUAL);
    }
    calls.put("java/util/regex/Matcher.groupCount", Opcode.INVOKEVIRTUAL);
    calls.put("java/lang/invoke/MethodType.parameterCount", Opcode.INVOKEVIRTUAL);
    for (var kind : List.of("", "Byte", "Char", "Short", "Int", "Long", "Float", "Double")) {
      calls.put("java/nio/" + kind + "Buffer.remaining", Opcode.INVOKEVIRTUAL);
      calls.put("java/nio/" + kind + "Buffer.capacity", Opcode.INVOKEVIRTUAL);
    }
    calls.put("java/lang/constant/MethodTypeDesc.parameterCount", Opcode.INVOKEINTERFACE);
    return Map.copyOf(calls);
  }

  /// Every relation but the jump's own and its two changes in the sufficient set, its opposite
  /// first.
  private static List<Relation> others(Relation relation) {
    var others = new ArrayList<Relation>(List.of(relation.negated()));
    Arrays.stream(Relation.values())
        .filter(other -> other != relation && other != relation.negated())
        .filter(other -> !relation.sufficient().contains(other))
        .forEach(others::add);
    return others;
  }

  /// Whether what the jump compares are numbers, not `boolean`s: a jump that orders them (`<`) or
  /// follows `lcmp`, or one side is a value no `boolean` can be.
  private static boolean ofNumbers(Jump jump, List<Instruction> before, List<CodeElement> code) {
    var relation = jump.relation();
    if (relation != Relation.EQ && relation != Relation.NE) {
      return true;
    }
    if (before.isEmpty()) {
      return false;
    }
    var top = before.getLast();
    if (compares(top.opcode()) || wholeNumber(before, before.size() - 1, code)) {
      return true;
    }
    // `a == b`: the instruction before `b` pushed `a`, when `b` took nothing off the stack.
    return jump.family() == Family.TWO_INTS
        && before.size() >= 2
        && pushesOnly(top)
        && wholeNumber(before, before.size() - 2, code);
  }

  /// Whether `before[index]` puts an `int` on the stack that isn't a `boolean`.
  private static boolean wholeNumber(List<Instruction> before, int index, List<CodeElement> code) {
    return switch (before.get(index)) {
      case ConstantInstruction constant ->
          constant.constantValue() instanceof Integer value && value != 0 && value != 1;
      case LoadInstruction load ->
          load.opcode().name().startsWith("ILOAD") && wholeNumberSlot(code, load.slot());
      case FieldInstruction field ->
          (field.opcode() == Opcode.GETFIELD || field.opcode() == Opcode.GETSTATIC)
              && WHOLE_NUMBER_TYPES.contains(field.typeSymbol().descriptorString());
      case InvokeInstruction call ->
          WHOLE_NUMBER_TYPES.contains(call.typeSymbol().returnType().descriptorString());
      case ConvertInstruction convert -> convert.opcode() != Opcode.I2L;
      case ArrayLoadInstruction load ->
          load.opcode() == Opcode.IALOAD
              || load.opcode() == Opcode.CALOAD
              || load.opcode() == Opcode.SALOAD;
      case OperatorInstruction operation -> wholeNumberOperation(before, index, operation, code);
      default -> false;
    };
  }

  /// Arithmetic on `int`s, or `&`, `|` or `^` whose last operand is a whole number: `(flags & MASK)
  /// != 0`, not `a & b` on two `boolean`s.
  private static boolean wholeNumberOperation(
      List<Instruction> before, int index, OperatorInstruction operation, List<CodeElement> code) {
    var opcode = operation.opcode();
    if (WHOLE_NUMBER_ARITHMETIC.contains(opcode)) {
      return true;
    }
    boolean bitwise = opcode == Opcode.IAND || opcode == Opcode.IOR || opcode == Opcode.IXOR;
    return bitwise && index > 0 && wholeNumber(before, index - 1, code);
  }

  /// Whether every local the debug table names in `slot` is a whole number: javac reuses a slot for
  /// variables in different scopes. False with no table.
  private static boolean wholeNumberSlot(List<CodeElement> code, int slot) {
    var types =
        code.stream()
            .filter(LocalVariable.class::isInstance)
            .map(LocalVariable.class::cast)
            .filter(local -> local.slot() == slot)
            .map(local -> local.typeSymbol().descriptorString())
            .toList();
    return !types.isEmpty() && WHOLE_NUMBER_TYPES.containsAll(types);
  }

  /// An instruction that takes nothing off the stack and pushes one value.
  private static boolean pushesOnly(Instruction instruction) {
    return instruction instanceof ConstantInstruction
        || instruction instanceof LoadInstruction
        || (instruction instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC)
        || (instruction instanceof InvokeInstruction call
            && call.opcode() == Opcode.INVOKESTATIC
            && call.typeSymbol().parameterCount() == 0);
  }

  private static boolean compares(Opcode opcode) {
    return opcode == Opcode.LCMP || nanOrdered(opcode);
  }

  /// `fcmpl` and the like: comparisons of `float`s and `double`s, which say where `NaN` goes.
  private static boolean nanOrdered(Opcode opcode) {
    return switch (opcode) {
      case FCMPL, FCMPG, DCMPL, DCMPG -> true;
      default -> false;
    };
  }

  /// The changes whose jump treats `NaN` as Java's comparison does, whichever way javac compiled
  /// the condition: `fcmpl` gives `-1` for it, `fcmpg` `1`. The original jump says which ways it
  /// could have been compiled, by how it treats `NaN` itself; where it doesn't say (`==` and `!=`),
  /// a change must be right both ways.
  private static List<Relation> keepingNan(
      Opcode compare, Relation original, List<Relation> wanted) {
    int nan = compare == Opcode.FCMPG || compare == Opcode.DCMPG ? 1 : -1;
    var ways =
        List.of(true, false).stream()
            .filter(inverted -> original.holds(nan) == takenOnNan(inverted, original))
            .toList();
    if (ways.isEmpty()) {
      return List.of(); // not as javac writes it: nothing to go on
    }
    return wanted.stream()
        .filter(change -> ways.stream().allMatch(way -> keepsNan(way, original, change, nan)))
        .toList();
  }

  /// Whether the change's jump is right for `NaN`, and gives the same answer there as the original
  /// unless it reverses it: `a < b` to `a != b` would differ for `NaN` as well as where `a > b`,
  /// and a card that names one side would be wrong.
  private static boolean keepsNan(boolean inverted, Relation original, Relation change, int nan) {
    boolean right = change.holds(nan) == takenOnNan(inverted, change);
    boolean reversed = change == original.negated();
    return right && (reversed || change.holds(nan) == original.holds(nan));
  }

  /// Whether a jump on `relation` should be taken for `NaN`: the source's comparison is false then,
  /// unless it's `!=`, and an inverted jump is taken when the source's is false.
  private static boolean takenOnNan(boolean inverted, Relation relation) {
    var source = inverted ? relation.negated() : relation;
    boolean holds = source == Relation.NE;
    return inverted != holds;
  }

  /// The kinds of conditional jump, by what they compare.
  enum Family {
    TWO_INTS("IF_ICMP"),
    WITH_ZERO("IF"),
    TWO_REFERENCES("IF_ACMP"),
    WITH_NULL("IF");

    private final String prefix;

    Family(String prefix) {
      this.prefix = prefix;
    }

    /// Whether what it compares can be numbers, and so ordered.
    boolean numbers() {
      return this == TWO_INTS || this == WITH_ZERO;
    }

    Opcode opcode(Relation relation) {
      if (this == WITH_NULL) {
        return relation == Relation.EQ ? Opcode.IFNULL : Opcode.IFNONNULL;
      }
      return Opcode.valueOf(prefix + relation.name());
    }
  }

  /// A conditional jump: what it compares, and the relation it's taken for.
  record Jump(Family family, Relation relation) {
    static Jump of(Opcode opcode) {
      var name = opcode.name();
      if (opcode == Opcode.IFNULL || opcode == Opcode.IFNONNULL) {
        return new Jump(Family.WITH_NULL, opcode == Opcode.IFNULL ? Relation.EQ : Relation.NE);
      }
      var family =
          name.startsWith("IF_ICMP")
              ? Family.TWO_INTS
              : name.startsWith("IF_ACMP") ? Family.TWO_REFERENCES : Family.WITH_ZERO;
      return new Jump(family, Relation.valueOf(name.substring(name.length() - 2)));
    }
  }
}
