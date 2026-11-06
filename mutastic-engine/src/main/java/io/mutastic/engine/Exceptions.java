package io.mutastic.engine;

import io.mutastic.api.Mutator.Change;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// What `mutastic/drop-catch-type` and `mutastic/remove-throw` make of a method.
///
/// `catch (A | B e)` is two entries in the exception table with one handler, so a handler with two
/// types is a multi-catch: nothing else javac writes looks like that. A `finally` has no change of
/// its own, as javac copies it onto every way out.
///
/// A `throw` becomes a `pop`, and the code carries on with whatever comes next, which has to
/// verify: at the end of a method nothing does, and that mutant is skipped as unviable. javac's own
/// throws have none: the rethrow of a `finally`, a `synchronized` or a try-with-resources, of a
/// variable the source doesn't have, and the default it writes for a switch that needs none.
final class Exceptions {
  private static final String NO_CATCH = "no catch of ";
  private static final String REMOVE = "remove throw";

  private static final Set<String> SYNTHETIC_THROWS =
      Set.of("java/lang/MatchException", "java/lang/IncompatibleClassChangeError");

  private Exceptions() {}

  /// One change per type, at a multi-catch handler's first instruction.
  static List<String> droppedCatches(List<CodeElement> code, int at) {
    var labels = new ArrayList<String>();
    var handlers = handlersAt(code, at);
    for (var handler : handlers) {
      var types = new LinkedHashSet<String>();
      for (var element : code) {
        if (element instanceof ExceptionCatch entry && entry.handler().equals(handler)) {
          entry.catchType().ifPresent(type -> types.add(type.asInternalName()));
        }
      }
      if (types.size() > 1) {
        types.forEach(type -> labels.add(NO_CATCH + type));
      }
    }
    return labels;
  }

  /// The labels bound just before the instruction at `code[at]`.
  static Set<Label> handlersAt(List<CodeElement> code, int at) {
    var labels = new LinkedHashSet<Label>();
    for (int index = at - 1; index >= 0 && !(code.get(index) instanceof Instruction); index--) {
      if (code.get(index) instanceof LabelTarget target) {
        labels.add(target.label());
      }
    }
    return labels;
  }

  /// Whether the mutant's change takes this entry out, of the handlers its sites start.
  static boolean drops(ExceptionCatch entry, Set<Label> handlers, String label) {
    return handlers.contains(entry.handler())
        && entry
            .catchType()
            .filter(type -> label.equals(NO_CATCH + type.asInternalName()))
            .isPresent();
  }

  /// A `throw` of the source's, named for what it throws where the code just before says.
  static List<Change> removedThrow(List<CodeElement> code, int at, Stores stores) {
    if (!(code.get(at) instanceof ThrowInstruction)) {
      return List.of();
    }
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    var last = before.isEmpty() ? null : before.getLast();
    return switch (last) {
      case LoadInstruction load ->
          stores
              .variableAt(load.slot(), at)
              .map(name -> List.of(new Change(REMOVE + " " + name, assertion(code, at))))
              .orElse(List.of());
      case InvokeInstruction call when call.name().equalsString("<init>") -> {
        var owner = call.owner().asInternalName();
        yield SYNTHETIC_THROWS.contains(owner)
            ? List.of()
            : List.of(
                new Change(
                    REMOVE + " " + owner.substring(owner.lastIndexOf('/') + 1),
                    assertion(code, at)));
      }
      case null, default -> List.of(new Change(REMOVE, assertion(code, at)));
    };
  }

  /// javac's `assert`: a jump past the throw, on `$assertionsDisabled`. What a test of one shows is
  /// the assert, not the code.
  private static Optional<String> assertion(List<CodeElement> code, int at) {
    var past = pastTheThrow(code, at);
    for (int index = 1; past.isPresent() && index < at; index++) {
      if (code.get(index) instanceof BranchInstruction jump
          && jump.opcode() == Opcode.IFNE
          && jump.target().equals(past.get())
          && readsAssertionsDisabled(code, index)) {
        return Optional.of("arid: only says an assert failed");
      }
    }
    return Optional.empty();
  }

  private static boolean readsAssertionsDisabled(List<CodeElement> code, int at) {
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    return !before.isEmpty()
        && before.getLast() instanceof FieldInstruction field
        && field.name().equalsString("$assertionsDisabled");
  }

  private static Optional<Label> pastTheThrow(List<CodeElement> code, int at) {
    for (int index = at + 1; index < code.size(); index++) {
      if (code.get(index) instanceof LabelTarget target) {
        return Optional.of(target.label());
      }
      if (code.get(index) instanceof Instruction) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
