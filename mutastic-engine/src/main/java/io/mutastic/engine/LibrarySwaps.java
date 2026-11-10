package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.util.List;
import java.util.Optional;

/// Swaps between similar JDK methods that can't change the result, judged by the code around the
/// call, whichever rules file asks for them:
///
/// - `s.indexOf(x) >= 0`, and the like: both search all of the text, and both give `-1` only when
///   it isn't there, so a test of whether it's there can't tell them apart.
/// - `Math.floor((double) count)`: a `double` made from a whole number is whole, so it rounds to
///   itself either way.
final class LibrarySwaps {
  private LibrarySwaps() {}

  /// Why renaming the call at `code[at]` to `to` can't change the result, if it can't.
  static Optional<String> equivalent(
      InvokeInstruction call, String to, List<CodeElement> code, int at) {
    var name = call.name().stringValue();
    if (isString(call)
        && isSearchPair(name, to)
        && searchesAllOfIt(call)
        && onlyTestsFound(code, at)) {
      return Optional.of(
          "equivalent: `%s` and `%s` agree on whether it's there".formatted(name, to));
    }
    if (isMath(call) && isRoundingPair(name, to) && convertedFromWhole(code, at)) {
      return Optional.of(
          ("equivalent: differs only when the value `%s` takes has a fraction. That can't happen"
                  + " because it's a whole number, which rounds to itself with `%s` and `%s`.")
              .formatted(name, name, to));
    }
    return Optional.empty();
  }

  private static boolean isString(InvokeInstruction call) {
    return call.owner().asInternalName().equals("java/lang/String");
  }

  private static boolean isMath(InvokeInstruction call) {
    return call.owner().asInternalName().equals("java/lang/Math");
  }

  private static boolean isSearchPair(String name, String to) {
    return (name.equals("indexOf") && to.equals("lastIndexOf"))
        || (name.equals("lastIndexOf") && to.equals("indexOf"));
  }

  private static boolean isRoundingPair(String name, String to) {
    return (name.equals("floor") && to.equals("ceil"))
        || (name.equals("ceil") && to.equals("floor"));
  }

  /// One argument: a search with a starting point looks at different parts of the text.
  private static boolean searchesAllOfIt(InvokeInstruction call) {
    return call.typeSymbol().parameterCount() == 1;
  }

  /// The result goes straight into a test of `-1` or of less than zero: `< 0`, `>= 0`, `== -1`, `!=
  /// -1`, `> -1` or `<= -1`, and it isn't kept.
  private static boolean onlyTestsFound(List<CodeElement> code, int at) {
    var after = instructionsAfter(code, at);
    if (after.isEmpty()) {
      return false;
    }
    var first = after.getFirst().opcode();
    if (first == Opcode.IFLT || first == Opcode.IFGE) {
      return true;
    }
    return after.size() == 2
        && after.getFirst() instanceof ConstantInstruction constant
        && Integer.valueOf(-1).equals(constant.constantValue())
        && comparesWithMinusOne(after.get(1).opcode());
  }

  private static boolean comparesWithMinusOne(Opcode opcode) {
    return switch (opcode) {
      case IF_ICMPEQ, IF_ICMPNE, IF_ICMPGT, IF_ICMPLE -> true;
      default -> false;
    };
  }

  /// The next two instructions, stopping at a label: past one, other code can jump in.
  private static List<Instruction> instructionsAfter(List<CodeElement> code, int at) {
    var found = new java.util.ArrayList<Instruction>();
    for (int index = at + 1; index < code.size() && found.size() < 2; index++) {
      if (code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction instruction) {
        found.add(instruction);
      }
    }
    return found;
  }

  /// The argument was just converted from an `int` or `long`, with nothing able to jump in between.
  private static boolean convertedFromWhole(List<CodeElement> code, int at) {
    for (int index = at - 1; index >= 0; index--) {
      switch (code.get(index)) {
        case LabelTarget _ -> {
          return false;
        }
        case ConvertInstruction convert -> {
          return convert.opcode() == Opcode.I2D || convert.opcode() == Opcode.L2D;
        }
        case Instruction _ -> {
          return false;
        }
        default -> {}
      }
    }
    return false;
  }
}
