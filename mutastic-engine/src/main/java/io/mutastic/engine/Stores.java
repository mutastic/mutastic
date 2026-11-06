package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/// One method's assignments to its local variables, for
/// [io.mutastic.api.BuiltIn#REMOVE_ASSIGNMENT]: which of them give a variable that already has a
/// value a new one, and which of those values anything reads.
final class Stores {
  private final List<CodeElement> code;
  private final Map<Label, Integer> positions = new HashMap<>();

  Stores(List<CodeElement> code) {
    this.code = code;
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof LabelTarget target) {
        positions.put(target.label(), at);
      }
    }
  }

  /// The variable the store at `at` gives a new value, if it already has one. javac's table of
  /// local variables starts each one's range just after its first definite assignment, so a store
  /// inside a range is a re-assignment, and one outside is a declaration: taking that away leaves
  /// nothing to read, and the class wouldn't load. Most of javac's own temporaries aren't in the
  /// table, and those that are, as a `switch` on a `String` in a lambda has, have a `$` in their
  /// name. Code compiled without the table has no such stores: nothing says what's a variable, or
  /// what it's called.
  Optional<String> reassigned(int at) {
    if (!(code.get(at) instanceof StoreInstruction store)) {
      return Optional.empty();
    }
    return code.stream()
        .flatMap(element -> element instanceof LocalVariable local ? Stream.of(local) : Stream.of())
        .filter(local -> local.slot() == store.slot() && inRange(local, at))
        .map(local -> local.name().stringValue())
        .filter(name -> !name.contains("$"))
        .findFirst();
  }

  private boolean inRange(LocalVariable local, int at) {
    return position(local.startScope()) <= at && at < position(local.endScope());
  }

  private int position(Label label) {
    return positions.getOrDefault(label, code.size());
  }

  /// Whether anything can read the value the store at `at` leaves.
  boolean valueIsRead(int at) {
    return readFrom(at + 1, ((StoreInstruction) code.get(at)).slot());
  }

  /// A parameter that holds an object, from the table of local variables.
  record Parameter(int slot, String name) {}

  /// The method's parameters that hold an object and that the table names. As for a store, a name
  /// with a `$` is javac's own, and code without the table has none.
  List<Parameter> objectParameters(MethodModel method) {
    var found = new ArrayList<Parameter>();
    int slot = method.flags().has(AccessFlag.STATIC) ? 0 : 1;
    for (var type : method.methodTypeSymbol().parameterList()) {
      if (!type.isPrimitive()) {
        int at = slot;
        namedFromTheStart(slot).ifPresent(name -> found.add(new Parameter(at, name)));
      }
      slot += TypeKind.from(type).slotSize();
    }
    return found;
  }

  /// Whether anything can read the value a parameter comes in with.
  boolean parameterIsRead(int slot) {
    return readFrom(0, slot);
  }

  private Optional<String> namedFromTheStart(int slot) {
    int first = firstInstruction();
    return code.stream()
        .flatMap(element -> element instanceof LocalVariable local ? Stream.of(local) : Stream.of())
        .filter(local -> local.slot() == slot && position(local.startScope()) <= first)
        .map(local -> local.name().stringValue())
        .filter(name -> !name.contains("$"))
        .findFirst();
  }

  private int firstInstruction() {
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof Instruction) {
        return at;
      }
    }
    return code.size();
  }

  /// Whether some path from `from`, through jumps and exception handlers, reaches a read of the
  /// variable before another store to it.
  private boolean readFrom(int from, int slot) {
    var handlers = handlers();
    var seen = new BitSet();
    var waiting = new ArrayDeque<Integer>(List.of(from));
    while (!waiting.isEmpty()) {
      int here = waiting.pop();
      if (here < code.size() && !seen.get(here)) {
        seen.set(here);
        var element = code.get(here);
        if (reads(element, slot)) {
          return true;
        }
        handlers.stream()
            .filter(handler -> handler.covers(here))
            .forEach(handler -> waiting.push(handler.at()));
        if (!(element instanceof StoreInstruction store && store.slot() == slot)) {
          next(element, here).forEach(waiting::push);
        }
      }
    }
    return false;
  }

  private static boolean reads(CodeElement element, int slot) {
    return (element instanceof LoadInstruction load && load.slot() == slot)
        || (element instanceof IncrementInstruction increment && increment.slot() == slot);
  }

  /// An exception handler: the code it covers, and where it starts.
  private record Handler(int from, int to, int at) {
    boolean covers(int here) {
      return from <= here && here < to;
    }
  }

  private List<Handler> handlers() {
    var handlers = new ArrayList<Handler>();
    for (CodeElement element : code) {
      if (element instanceof ExceptionCatch handler) {
        handlers.add(
            new Handler(
                position(handler.tryStart()),
                position(handler.tryEnd()),
                position(handler.handler())));
      }
    }
    return handlers;
  }

  /// Where the code can go from `element`, at `here`, without an exception.
  private List<Integer> next(CodeElement element, int here) {
    return switch (element) {
      case ReturnInstruction _, ThrowInstruction _ -> List.of();
      case BranchInstruction jump when isAlwaysTaken(jump) -> List.of(position(jump.target()));
      case BranchInstruction jump -> List.of(here + 1, position(jump.target()));
      case TableSwitchInstruction choice -> targets(choice.defaultTarget(), choice.cases());
      case LookupSwitchInstruction choice -> targets(choice.defaultTarget(), choice.cases());
      default -> List.of(here + 1);
    };
  }

  private static boolean isAlwaysTaken(BranchInstruction jump) {
    return jump.opcode() == Opcode.GOTO || jump.opcode() == Opcode.GOTO_W;
  }

  private List<Integer> targets(Label otherwise, List<SwitchCase> cases) {
    var targets = new ArrayList<Integer>(List.of(position(otherwise)));
    cases.forEach(each -> targets.add(position(each.target())));
    return targets;
  }
}
