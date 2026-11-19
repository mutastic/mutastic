package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator.Change;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// What `mutastic/string-constant` and `mutastic/regex-constant` make of an `ldc` of text.
///
/// A string becomes `""`, or an empty one a word that isn't empty. Text that only tells a person
/// something is arid: what goes into a log call, an exception or a failed check. An exception is
/// one thrown straight away, or whose real supertypes reach `Throwable`; its name says nothing. An
/// annotation's strings aren't in the code, and `"a" + x` keeps its text in javac's recipe, so
/// neither has a mutant.
///
/// A regular expression is only changed where the text goes straight into `Pattern.compile`,
/// `matches`, `split`, `replaceAll` or `replaceFirst`, and only to one that compiles: an anchor
/// gone, a character class or `\d` the other way round, a quantifier gone. Those are the changes a
/// test with one more line of input tells apart, and they say what the expression is for.
final class TextConstants {
  static final String SENTINEL = "mutastic";

  private static final int SHOWN = 40;

  /// By its full name, as a logger is: the one library whose checks are known to take a message.
  /// Anyone else's `Preconditions` may do anything with a string.
  private static final String PRECONDITIONS = "com/google/common/base/Preconditions";

  /// Why an anchor taken off a pattern that must match all of the text is skipped.
  static final String WHOLE_MATCH =
      "equivalent: differs only when a match is part of the text. That can't happen because it"
          + " has to match all of the text anyway.";

  private TextConstants() {}

  /// A call a value goes straight into, where it is among the call's values (`0` is an instance
  /// call's receiver, or a static call's first argument), and where the call is.
  private record Consumer(InvokeInstruction call, int position, int index) {}

  static List<Change> strings(
      List<CodeElement> code, int at, Stores stores, Supertypes supertypes) {
    if (!(code.get(at) instanceof ConstantInstruction constant)
        || !(constant.constantValue() instanceof String text)
        || isStringSwitchTest(code, at, stores)) {
      return List.of();
    }
    var to = text.isEmpty() ? SENTINEL : "";
    return List.of(
        new Change(
            "string %s to %s".formatted(shown(text), quoted(to)),
            onlyTellsAPerson(code, at, supertypes)));
  }

  /// javac's test in a `String` switch: `aload temporary; ldc "a"; equals`.
  private static boolean isStringSwitchTest(List<CodeElement> code, int at, Stores stores) {
    var next = SiteSkips.instructionsAfter(code, at, 1);
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    return !next.isEmpty()
        && next.getFirst() instanceof InvokeInstruction call
        && call.owner().asInternalName().equals("java/lang/String")
        && call.name().equalsString("equals")
        && !before.isEmpty()
        && before.getLast() instanceof LoadInstruction load
        && stores.variableAt(load.slot(), at).isEmpty();
  }

  /// Following the value through what only formats it, why nothing but a person reads it, if so.
  private static Optional<String> onlyTellsAPerson(
      List<CodeElement> code, int at, Supertypes supertypes) {
    var next = consumer(code, at);
    while (next.isPresent()) {
      var call = next.get().call();
      var why = aridCall(call, code, next.get().index(), supertypes);
      if (why.isPresent() || !isFormatting(call)) {
        return why;
      }
      next = consumer(code, next.get().index());
    }
    return Optional.empty();
  }

  /// What only makes more text of it: `format`, or a builder, as javac writes `"a" + x` for a class
  /// older than Java 9. The next call's on what this one leaves, a new builder included.
  private static boolean isFormatting(InvokeInstruction call) {
    var name = call.name().stringValue();
    return switch (call.owner().asInternalName()) {
      case "java/lang/String" -> name.equals("format") || name.equals("formatted");
      case "java/lang/StringBuilder", "java/lang/StringBuffer" ->
          name.equals("<init>") || name.equals("append") || name.equals("toString");
      default -> false;
    };
  }

  private static Optional<String> aridCall(
      InvokeInstruction call, List<CodeElement> code, int at, Supertypes supertypes) {
    var owner = call.owner().asInternalName();
    if (Mutations.OUTPUT_OWNERS.contains(owner)) {
      return Optional.of("arid: only writes output");
    }
    if (call.name().equalsString("<init>") && isThrowable(owner, code, at, supertypes)) {
      return Optional.of("arid: only an exception's message");
    }
    if ((owner.equals("java/util/Objects")
            && call.name().stringValue().startsWith("requireNonNull"))
        || owner.equals(PRECONDITIONS)) {
      return Optional.of("arid: only a failed check's message");
    }
    return Optional.empty();
  }

  /// It's thrown straight away, which only a `Throwable` can be, or its supertypes say it is one.
  private static boolean isThrowable(
      String owner, List<CodeElement> code, int at, Supertypes supertypes) {
    var next = SiteSkips.instructionsAfter(code, at, 1);
    return (!next.isEmpty() && next.getFirst().opcode() == Opcode.ATHROW)
        || supertypes.isThrowable(owner);
  }

  static List<Change> regexes(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof ConstantInstruction constant)
        || !(constant.constantValue() instanceof String text)) {
      return List.of();
    }
    var taken = consumer(code, at).filter(TextConstants::takesARegex);
    if (taken.isEmpty()) {
      return List.of();
    }
    boolean whole = matchesAllOfIt(taken.get().call());
    var changes = new ArrayList<Change>();
    for (var to : regexChanges(text)) {
      changes.add(
          new Change(
              regexLabel(text, to),
              whole && isAnchorGone(text, to) ? Optional.of(WHOLE_MATCH) : Optional.empty()));
    }
    return changes;
  }

  private static String regexLabel(String from, String to) {
    return "regex %s to %s".formatted(quoted(from), quoted(to));
  }

  private static boolean takesARegex(Consumer consumer) {
    var call = consumer.call();
    var name = call.name().stringValue();
    return switch (call.owner().asInternalName()) {
      case "java/util/regex/Pattern" ->
          consumer.position() == 0
              && call.opcode() == Opcode.INVOKESTATIC
              && (name.equals("compile") || name.equals("matches"));
      case "java/lang/String" ->
          consumer.position() == 1
              && (name.equals("matches")
                  || name.equals("split")
                  || name.equals("replaceAll")
                  || name.equals("replaceFirst"));
      default -> false;
    };
  }

  private static boolean matchesAllOfIt(InvokeInstruction call) {
    return call.name().equalsString("matches");
  }

  private static boolean isAnchorGone(String from, String to) {
    return (from.startsWith("^") && to.equals(from.substring(1)))
        || (from.endsWith("$") && to.equals(from.substring(0, from.length() - 1)));
  }

  /// Follows the value at `code[at]` to the call that takes it, on every path the code can take
  /// while the value is on the stack; none if any path does anything else with it.
  private static Optional<Consumer> consumer(List<CodeElement> code, int at) {
    return new Follow().from(code, at);
  }

  /// What a regular expression can become: each compiles, and each is different.
  static List<String> regexChanges(String regex) {
    var changes = new ArrayList<String>();
    if (regex.startsWith("^")) {
      changes.add(regex.substring(1));
    }
    if (regex.endsWith("$") && !regex.endsWith("\\$")) {
      changes.add(regex.substring(0, regex.length() - 1));
    }
    addTokens(regex, changes);
    return changes.stream()
        .distinct()
        .filter(to -> !to.equals(regex))
        .filter(TextConstants::compiles)
        .toList();
  }

  /// Walks the expression past what's escaped: a class negated, `\d` to `\D`, a quantifier gone.
  private static void addTokens(String regex, List<String> changes) {
    boolean inClass = false;
    int at = 0;
    while (at < regex.length()) {
      char here = regex.charAt(at);
      int next = at + 1;
      if (here == '\\') {
        shorthand(regex, at).ifPresent(changes::add);
        next = at + 2;
      } else if (here == '[' && !inClass) {
        changes.add(negatedClass(regex, at));
        inClass = true;
      } else if (here == ']') {
        inClass = false;
      } else if (!inClass && isQuantifier(regex, at)) {
        next = quantifierEnd(regex, at);
        changes.add(regex.substring(0, at) + regex.substring(next));
      }
      at = next;
    }
  }

  private static Optional<String> shorthand(String regex, int at) {
    if (at + 1 >= regex.length() || "dwsDWS".indexOf(regex.charAt(at + 1)) < 0) {
      return Optional.empty();
    }
    char letter = regex.charAt(at + 1);
    char other =
        Character.isUpperCase(letter)
            ? Character.toLowerCase(letter)
            : Character.toUpperCase(letter);
    return Optional.of(regex.substring(0, at + 1) + other + regex.substring(at + 2));
  }

  private static String negatedClass(String regex, int at) {
    return at + 1 < regex.length() && regex.charAt(at + 1) == '^'
        ? regex.substring(0, at + 1) + regex.substring(at + 2)
        : regex.substring(0, at + 1) + "^" + regex.substring(at + 1);
  }

  /// `+`, `*`, `?` or `{`, with something before it to repeat: the `?` of `(?:` isn't one.
  private static boolean isQuantifier(String regex, int at) {
    return at > 0 && "+*?{".indexOf(regex.charAt(at)) >= 0 && regex.charAt(at - 1) != '(';
  }

  /// Just past the quantifier, with the `?` or `+` that makes it lazy or possessive.
  private static int quantifierEnd(String regex, int at) {
    int end = at + 1;
    if (regex.charAt(at) == '{') {
      int close = regex.indexOf('}', at);
      end = close < 0 ? end : close + 1;
    }
    return end < regex.length() && "?+".indexOf(regex.charAt(end)) >= 0 ? end + 1 : end;
  }

  private static boolean compiles(String regex) {
    try {
      Pattern.compile(regex);
      return true;
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  /// In quotes and escaped, with a long string cut short: a label names it, and doesn't hold it.
  private static String shown(String text) {
    var written = quoted(text);
    return written.length() > SHOWN ? written.substring(0, SHOWN) + "…\"" : written;
  }

  private static String quoted(String text) {
    return "\""
        + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        + "\"";
  }

  /// The text in its place: `""` or the word for a string, and for a regular expression the change
  /// the label names, worked out again as a label doesn't have to be read back.
  static void write(CodeBuilder builder, ConstantInstruction constant, Mutant mutant) {
    var text = (String) constant.constantValue();
    if (mutant.operator() == BuiltIn.STRING_CONSTANT) {
      builder.ldc(text.isEmpty() ? SENTINEL : "");
      return;
    }
    builder.ldc(
        regexChanges(text).stream()
            .filter(to -> regexLabel(text, to).equals(mutant.replacement()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no such change: " + mutant.replacement())));
  }

  /// A walk along the code after a value, counting what's on the stack from it up. A jump ahead
  /// carries the count to its label, and code no path reaches with the value is passed over. The
  /// walk stops where something other than a call takes the value, where a jump goes back or past
  /// the call, or at an instruction it doesn't know: the value may go somewhere else then.
  private static final class Follow {
    /// What an instruction takes off the stack and leaves on it, in values: a `long` is one.
    private record Effect(int takes, int leaves) {}

    /// An instruction the walk doesn't follow, as if it took the whole stack.
    private static final Effect UNKNOWN = new Effect(Integer.MAX_VALUE, 0);

    private final Set<Label> passed = new HashSet<>();
    private final Map<Label, Integer> ahead = new HashMap<>();
    private int depth = 1;
    private boolean reached = true;

    Optional<Consumer> from(List<CodeElement> code, int at) {
      for (int index = at + 1; index < code.size(); index++) {
        var element = code.get(index);
        if (reached && element instanceof InvokeInstruction call && takes(call) >= depth) {
          return ahead.isEmpty()
              ? Optional.of(new Consumer(call, takes(call) - depth, index))
              : Optional.empty();
        }
        if (!pass(element)) {
          return Optional.empty();
        }
      }
      return Optional.empty();
    }

    /// Whether the value can still be followed past this element.
    private boolean pass(CodeElement element) {
      return switch (element) {
        case LabelTarget target -> arrive(target.label());
        case Instruction instruction when reached -> step(instruction);
        default -> true;
      };
    }

    /// At a label, what a jump to it carries is what falls through to it, and code after a `goto`
    /// is reached again with the value only by a jump that carried it.
    private boolean arrive(Label label) {
      passed.add(label);
      var carried = ahead.remove(label);
      if (reached) {
        return carried == null || carried == depth;
      }
      if (carried != null) {
        depth = carried;
        reached = true;
      }
      return true;
    }

    private boolean step(Instruction instruction) {
      if (instruction instanceof BranchInstruction branch) {
        return jump(branch);
      }
      var effect = effect(instruction);
      if (effect.takes() >= depth) {
        return false;
      }
      depth += effect.leaves() - effect.takes();
      return true;
    }

    private boolean jump(BranchInstruction branch) {
      int taken =
          switch (branch.opcode()) {
            case GOTO, GOTO_W -> 0;
            case IF_ICMPEQ,
                IF_ICMPNE,
                IF_ICMPLT,
                IF_ICMPGE,
                IF_ICMPGT,
                IF_ICMPLE,
                IF_ACMPEQ,
                IF_ACMPNE ->
                2;
            default -> 1;
          };
      if (taken >= depth || passed.contains(branch.target())) {
        return false;
      }
      depth -= taken;
      var before = ahead.putIfAbsent(branch.target(), depth);
      reached = taken > 0;
      return before == null || before == depth;
    }

    private static int takes(InvokeInstruction call) {
      return call.typeSymbol().parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
    }

    private static Effect effect(Instruction instruction) {
      return switch (instruction) {
        case LoadInstruction _, ConstantInstruction _, NewObjectInstruction _ -> new Effect(0, 1);
        case StoreInstruction _, MonitorInstruction _ -> new Effect(1, 0);
        case IncrementInstruction _, NopInstruction _ -> new Effect(0, 0);
        case ConvertInstruction _,
            TypeCheckInstruction _,
            NewReferenceArrayInstruction _,
            NewPrimitiveArrayInstruction _ ->
            new Effect(1, 1);
        case NewMultiArrayInstruction array -> new Effect(array.dimensions(), 1);
        case ArrayLoadInstruction _ -> new Effect(2, 1);
        case ArrayStoreInstruction _ -> new Effect(3, 0);
        case OperatorInstruction operator -> new Effect(unary(operator.opcode()) ? 1 : 2, 1);
        case FieldInstruction field -> field(field.opcode());
        case InvokeInstruction call -> new Effect(takes(call), returns(call.typeSymbol()));
        case InvokeDynamicInstruction call ->
            new Effect(call.typeSymbol().parameterCount(), returns(call.typeSymbol()));
        case StackInstruction stack -> stack(stack.opcode());
        default -> UNKNOWN;
      };
    }

    private static boolean unary(Opcode opcode) {
      return switch (opcode) {
        case INEG, LNEG, FNEG, DNEG, ARRAYLENGTH -> true;
        default -> false;
      };
    }

    private static int returns(MethodTypeDesc type) {
      return type.returnType().equals(ConstantDescs.CD_void) ? 0 : 1;
    }

    private static Effect field(Opcode opcode) {
      return switch (opcode) {
        case GETSTATIC -> new Effect(0, 1);
        case GETFIELD, PUTSTATIC -> new Effect(1, opcode == Opcode.GETFIELD ? 1 : 0);
        default -> new Effect(2, 0);
      };
    }

    /// Only what moves single values: the rest work in slots, and a `long` takes two of those.
    private static Effect stack(Opcode opcode) {
      return switch (opcode) {
        case POP -> new Effect(1, 0);
        case DUP -> new Effect(1, 2);
        case DUP_X1 -> new Effect(2, 3);
        case SWAP -> new Effect(2, 2);
        default -> UNKNOWN;
      };
    }
  }
}
