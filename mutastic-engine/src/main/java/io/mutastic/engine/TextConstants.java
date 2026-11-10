package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator.Change;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  /// Follows the value at `code[at]` along straight code to the call that takes it; none if a label
  /// comes first, or an instruction that does anything else to it.
  private static Optional<Consumer> consumer(List<CodeElement> code, int at) {
    int depth = 1;
    for (int index = at + 1; index < code.size() && depth > 0; index++) {
      var element = code.get(index);
      if (element instanceof LabelTarget) {
        return Optional.empty();
      }
      if (element instanceof InvokeInstruction call) {
        int taken =
            call.typeSymbol().parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
        if (taken >= depth) {
          return Optional.of(new Consumer(call, taken - depth, index));
        }
        boolean returns = !call.typeSymbol().returnType().equals(ConstantDescs.CD_void);
        depth += (returns ? 1 : 0) - taken;
      } else if (element instanceof Instruction instruction) {
        depth = depthAfter(instruction, depth);
      }
    }
    return Optional.empty();
  }

  /// How many values are on the stack from ours up, after an instruction that leaves ours where it
  /// is; `0` for one that doesn't, or that this doesn't follow.
  private static int depthAfter(Instruction instruction, int depth) {
    return switch (instruction) {
      case LoadInstruction _, ConstantInstruction _ -> depth + 1;
      case FieldInstruction field when field.opcode() == Opcode.GETSTATIC -> depth + 1;
      case FieldInstruction field when field.opcode() == Opcode.GETFIELD && depth > 1 -> depth;
      case NewReferenceArrayInstruction _ when depth > 1 -> depth;
      case ConvertInstruction _ when depth > 1 -> depth;
      case TypeCheckInstruction _ when depth > 1 -> depth;
      case ArrayStoreInstruction _ when depth > 3 -> depth - 3;
      case StackInstruction dup when dup.opcode() == Opcode.DUP && depth > 1 -> depth + 1;
      default -> 0;
    };
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
}
