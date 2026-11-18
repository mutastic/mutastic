package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// `library/reverse-ordering`: an order turned round, as the Rust edition's operator of that id
/// turns `a.cmp(&b)` into `b.cmp(&a)` and leaves out an ordering's `.reverse()`. Four changes, each
/// by its label, which is part of the mutant's id:
///
/// - `swap operands`: `a.compareTo(b)` becomes `b.compareTo(a)`, where `b` is of `a`'s type, or the
///   call is `Comparable`'s own. A result only tested for being `0` is skipped as equivalent: the
///   two are `0` together.
/// - `remove`: a comparator's, a list's or a deque's `reversed()` is left out, and what it was
///   called on goes on in its place.
/// - `keep argument`: `Collections.reverseOrder(c)` becomes `c`.
/// - `naturalOrder`: `Comparator.reverseOrder()` and `Collections.reverseOrder()` become
///   `Comparator.naturalOrder()`.
///
/// Not a rules file's operator, as a rule has one rewrite and this has four.
final class ReverseOrdering implements Mutator {
  static final ReverseOrdering INSTANCE = new ReverseOrdering();

  static final String ID = "library/reverse-ordering";
  static final String SWAP = "swap operands";
  static final String REMOVE = "remove";
  static final String KEEP_ARGUMENT = "keep argument";
  static final String NATURAL = "naturalOrder";

  private static final String COMPARATOR = "java/util/Comparator";
  private static final String COLLECTIONS = "java/util/Collections";
  private static final String COMPARABLE = "java/lang/Comparable";

  /// The types whose `reversed()` returns the type it's called on, as javac writes the call.
  private static final Set<String> REVERSIBLE =
      Set.of(
          COMPARATOR,
          "java/util/List",
          "java/util/Deque",
          "java/util/SequencedCollection",
          "java/util/LinkedList");

  /// The types a primitive is boxed to: `Integer.valueOf(n).compareTo(count)` boxes `count`.
  private static final Set<String> BOXES =
      Set.of(
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte",
          "java/lang/Character",
          "java/lang/Double",
          "java/lang/Float",
          "java/lang/Boolean");

  private ReverseOrdering() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String explanation() {
    return "An order turns round: `a.compareTo(b)` becomes `b.compareTo(a)`, a comparator's or a"
        + " list's `reversed()` is left out, `Collections.reverseOrder(c)` becomes `c`, and"
        + " `Comparator.reverseOrder()` becomes `naturalOrder()`. It goes unnoticed when no test"
        + " checks the order things come out in.";
  }

  @Override
  public boolean onCalls() {
    return true;
  }

  @Override
  public Tier tier() {
    return Tier.STRONGER;
  }

  @Override
  public List<Change> changes(Site site) {
    if (!(site.instruction() instanceof InvokeInstruction call)) {
      return List.of();
    }
    return change(call)
        .map(
            label ->
                label.equals(SWAP)
                    ? new Change(label, onlyTestedForZero(site.code(), site.index()))
                    : Change.of(label))
        .stream()
        .toList();
  }

  /// What the call becomes, by its label; none when it doesn't order anything.
  private static Optional<String> change(InvokeInstruction call) {
    return switch (call.name().stringValue()) {
      case "compareTo" -> isCompareTo(call) ? Optional.of(SWAP) : Optional.empty();
      case "reversed" -> isReversed(call) ? Optional.of(REMOVE) : Optional.empty();
      case "reverseOrder" -> reverseOrder(call);
      default -> Optional.empty();
    };
  }

  /// `reversed()` on a type whose `reversed()` returns that type, so what it's called on can go on
  /// in its place.
  private static boolean isReversed(InvokeInstruction call) {
    var type = call.typeSymbol();
    return type.parameterCount() == 0
        && REVERSIBLE.contains(call.owner().asInternalName())
        && type.returnType().equals(call.owner().asSymbol());
  }

  /// `Collections.reverseOrder(c)` keeps `c`; `Comparator.reverseOrder()` and
  /// `Collections.reverseOrder()` become `naturalOrder()`.
  private static Optional<String> reverseOrder(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    return switch (call.type().stringValue()) {
      case "(Ljava/util/Comparator;)Ljava/util/Comparator;" ->
          owner.equals(COLLECTIONS) ? Optional.of(KEEP_ARGUMENT) : Optional.empty();
      case "()Ljava/util/Comparator;" ->
          owner.equals(COLLECTIONS) || owner.equals(COMPARATOR)
              ? Optional.of(NATURAL)
              : Optional.empty();
      default -> Optional.empty();
    };
  }

  /// `a.compareTo(b)` returning an `int`, where `b` is of the type it's called on, or of any type
  /// for `Comparable`'s own, whose argument is the same type in a program that compiled: either
  /// way, `b` can be called on.
  private static boolean isCompareTo(InvokeInstruction call) {
    var type = call.typeSymbol();
    if (type.parameterCount() != 1 || !type.returnType().descriptorString().equals("I")) {
      return false;
    }
    var parameter = type.parameterType(0);
    return parameter.equals(call.owner().asSymbol())
        || (call.owner().asInternalName().equals(COMPARABLE)
            && parameter.equals(ClassDesc.of("java.lang.Object")));
  }

  /// Why swapping can't change the result, if it can't: it goes straight into a test of `== 0` or
  /// `!= 0`, and `a.compareTo(b)` is `0` exactly when `b.compareTo(a)` is.
  private static Optional<String> onlyTestedForZero(List<CodeElement> code, int at) {
    for (int index = at + 1; index < code.size(); index++) {
      switch (code.get(index)) {
        case LabelTarget _ -> {
          return Optional.empty();
        }
        case Instruction next -> {
          return next.opcode() == Opcode.IFEQ || next.opcode() == Opcode.IFNE
              ? Optional.of(
                  "equivalent: `a.compareTo(b)` and `b.compareTo(a)` are `0` for the same values,"
                      + " and only that is tested")
              : Optional.empty();
        }
        default -> {
          // line numbers
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void write(CodeBuilder builder, Instruction instruction, String label) {
    var call = (InvokeInstruction) instruction;
    switch (label) {
      case SWAP -> {
        builder.swap();
        builder.with(call);
      }
      case NATURAL ->
          builder.invokestatic(
              ClassDesc.ofInternalName(COMPARATOR),
              "naturalOrder",
              MethodTypeDesc.ofDescriptor("()Ljava/util/Comparator;"),
              true);
      case REMOVE, KEEP_ARGUMENT -> {
        // `reversed()` leaves its receiver, `reverseOrder(c)` its argument, where the result goes
      }
      default -> throw new IllegalArgumentException("no change called " + label);
    }
  }

  /// `b.compareTo(a)`, unless the source may not say it so: a number boxed to be compared,
  /// `n.compareTo(count)`, can't be called on, and `Comparable`'s own has an argument of another
  /// type than what it's called on, so the card says it in words.
  private static Optional<String> writtenSwapped(String original) {
    var owner = original.substring(original.indexOf(' ') + 1, original.lastIndexOf('.'));
    return owner.equals(COMPARABLE) || BOXES.contains(owner)
        ? Optional.empty()
        : Optional.of("{arg1}.compareTo({receiver})");
  }

  @Override
  public Words words(Mutant mutant) {
    var method = mutant.methodName() + "()";
    var name = CallRule.calledName(mutant.original());
    return switch (mutant.replacement()) {
      case SWAP ->
          words(
              "changed to b.compareTo(a)",
              "no test checks which way `compareTo` orders in " + method,
              writtenSwapped(mutant.original()));
      case REMOVE ->
          words("reversed() left out", "no test needs `.reversed()` in " + method, "{receiver}");
      case KEEP_ARGUMENT ->
          words("reverseOrder left out", "no test needs `reverseOrder` in " + method, "{arg1}");
      default ->
          words(
              name + " changed to naturalOrder",
              "no test tells `%s` from `naturalOrder` in %s".formatted(name, method),
              "Comparator.naturalOrder()");
    };
  }

  private static Words words(String changed, String headline, String written) {
    return words(changed, headline, Optional.of(written));
  }

  private static Words words(String changed, String headline, Optional<String> written) {
    return new Words(
        changed, headline, "use values out of order, and assert the order that comes out", written);
  }
}
