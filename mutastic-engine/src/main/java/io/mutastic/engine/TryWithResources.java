package io.mutastic.engine;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// The code a compiler writes to close a try-with-resources resource, and kotlinc to close what
/// `use` is given. The person asked for the resource to be closed, not for this code: removing a
/// close or reversing its null check would only give cards about a resource that's null, or a close
/// that throws while the body is throwing.
///
/// Each compiler's code is found by its exact shape, starting from the handler it writes for the
/// resource's own exception table entry, and from the slot that handler keeps the body's exception
/// in. That slot is the compiler's: no local variable table names it. A close the person wrote is
/// in no such handler, nor where one of its entries ends, so it's mutated, in a method with a
/// try-with-resources as much as in any other, and so is a pre-Java-7 close written out by hand,
/// whose exception has a name.
///
/// - **javac 11 and later**, for every `--release`: a `catch Throwable` entry over the body, whose
///   handler stores the exception, closes the resource (`if (r != null)` unless it came from `new`)
///   under an entry of its own whose handler adds what the close threw as suppressed, and rethrows
///   it. The close on the normal path is where each of the body's entries ends.
/// - **javac 7 to 10**, in a class file for Java 10 or before: a `catch Throwable` whose handler
///   keeps the exception as the primary one and rethrows it, followed by the `finally` handler that
///   closes the resource. The close is written out (`if (primary != null) try { r.close() } catch
///   ... else r.close()`), or, from javac 9, a call to the synthetic `$closeResource(primary, r)`.
///   A copy of the `finally` is where each of the body's entries ends.
/// - **kotlinc's `use`**: the same as javac 9's, with the standard library's `closeFinally(r,
///   primary)` for `$closeResource`.
/// - **ECJ**: a `finally` handler for each resource that closes it and rethrows, and a handler
///   around them all that keeps the first exception and adds any other as suppressed, `if (primary
///   == null) primary = t; else if (primary != t) primary.addSuppressed(t)`.
final class TryWithResources {
  static final String REASON = "generated: closes a try-with-resources resource";
  static final String USE = "generated: closes a `use` resource";

  /// The last class file version javac 10 writes, Java 10's. javac 11 and later write their own
  /// shape for every `--release`.
  private static final int JAVA_10 = 54;

  private static final String THROWABLE = "java/lang/Throwable";

  private static final Set<String> KOTLIN_CLOSES =
      Set.of("kotlin/io/CloseableKt", "kotlin/jdk7/AutoCloseableKt", "kotlin/AutoCloseableKt");

  private final List<CodeElement> code;
  private final boolean java10OrBefore;

  /// The position in [#code] of each instruction, in order.
  private final List<Integer> positions = new ArrayList<>();

  /// The instruction each label is on, as its index in [#positions].
  private final Map<Label, Integer> labels = new HashMap<>();

  private final List<Entry> entries = new ArrayList<>();
  private final List<LocalVariable> named = new ArrayList<>();
  private final Map<Integer, String> found = new HashMap<>();

  /// An exception table entry, by instruction: the first one it covers, the first it doesn't, and
  /// its handler's first.
  private record Entry(int start, int end, int handler, Optional<String> type) {
    boolean catchesThrowable() {
      return type.filter(THROWABLE::equals).isPresent();
    }

    boolean catchesAnything() {
      return type.isEmpty();
    }
  }

  private TryWithResources(ClassModel model, List<CodeElement> code) {
    this.code = code;
    this.java10OrBefore = model.majorVersion() <= JAVA_10;
    var catches = new ArrayList<ExceptionCatch>();
    for (int at = 0; at < code.size(); at++) {
      switch (code.get(at)) {
        case LabelTarget target -> labels.put(target.label(), positions.size());
        case ExceptionCatch entry -> catches.add(entry);
        case LocalVariable local -> named.add(local);
        case Instruction _ -> positions.add(at);
        default -> {}
      }
    }
    for (var entry : catches) {
      entries.add(
          new Entry(
              ordinal(entry.tryStart()),
              ordinal(entry.tryEnd()),
              ordinal(entry.handler()),
              entry.catchType().map(type -> type.asInternalName())));
    }
  }

  /// Where in `code` each instruction a compiler wrote to close a resource is, with why it's
  /// skipped. By position, as an instruction with no operand, like `athrow`, can be the same object
  /// wherever it is.
  static Map<Integer, String> closes(ClassModel model, List<CodeElement> code) {
    var scan = new TryWithResources(model, code);
    scan.find();
    return Map.copyOf(scan.found);
  }

  private void find() {
    if (entries.isEmpty()) {
      return;
    }
    for (var entry : entries) {
      if (entry.catchesThrowable() && !javac11(entry)) {
        javac7To10(entry);
      }
    }
    ecj();
  }

  // javac 11 and later

  /// `astore t; [aload r; ifnull end;] aload r; close(); goto end; astore x; aload t; aload x;
  /// addSuppressed; end: aload t; athrow`, the close under a `catch Throwable` of its own whose
  /// handler is the `astore x`.
  private boolean javac11(Entry body) {
    var handler = new Shape(body.handler(), Map.of()).store("t");
    var guarded = handler.copy().load("r").jump(Opcode.IFNULL, "end").close("r");
    var shape = guarded.matches() ? guarded : handler.copy().close("r");
    int close = shape.next - 1;
    shape.jump(Opcode.GOTO, "end").store("x");
    int suppressing = shape.next - 1;
    shape.suppress("t", "x").at("end").load("t").is(Opcode.ATHROW);
    if (!shape.matches()
        || !compilers(shape, "t", body.handler())
        || entries.stream()
            .noneMatch(
                entry ->
                    entry.catchesThrowable()
                        && entry.handler() == suppressing
                        && entry.start() <= close
                        && close < entry.end())) {
      return false;
    }
    mark(shape, REASON);
    var resource = Map.of("r", shape.slot("r"));
    endsOf(body.handler())
        .forEach(end -> markFirst(REASON, guardedClose(end, resource), close(end, resource)));
    return true;
  }

  private Shape guardedClose(int at, Map<String, Integer> slots) {
    return new Shape(at, slots).load("r").jump(Opcode.IFNULL, "skip").close("r");
  }

  private Shape close(int at, Map<String, Integer> slots) {
    return new Shape(at, slots).close("r");
  }

  // javac 7 to 10, and kotlinc's use

  /// How the `finally` closes the resource, from the slots of the primary exception `p` and the
  /// resource `r`.
  private enum Finally {
    /// javac 7 and 8, and 9 and 10 where `r` can be null.
    FULL(true),
    /// javac 9 and 10 where `r` comes from `new`.
    NOT_NULL(true),
    /// javac 9 and 10 with `$closeResource` where `r` can be null.
    HELPER(true),
    /// javac 9 and 10 with `$closeResource` where `r` comes from `new`.
    HELPER_NOT_NULL(true),
    /// kotlinc's `use`, with the standard library's `closeFinally`.
    KOTLIN(false);

    private final boolean javac;

    Finally(boolean javac) {
      this.javac = javac;
    }

    Shape after(Shape shape) {
      return switch (this) {
        case FULL -> written(shape.load("r").jump(Opcode.IFNULL, "end"));
        case NOT_NULL -> written(shape);
        case HELPER ->
            shape
                .load("r")
                .jump(Opcode.IFNULL, "end")
                .load("p")
                .load("r")
                .call(TryWithResources::isCloseResource);
        case HELPER_NOT_NULL -> shape.load("p").load("r").call(TryWithResources::isCloseResource);
        case KOTLIN -> shape.load("r").load("p").call(TryWithResources::isCloseFinally);
      };
    }

    /// `if (p != null) { try { r.close(); } catch (Throwable x) { p.addSuppressed(x); } } else {
    /// r.close(); }`.
    private static Shape written(Shape shape) {
      return shape
          .load("p")
          .jump(Opcode.IFNULL, "else")
          .close("r")
          .jump(Opcode.GOTO, "done")
          .store("x")
          .suppress("p", "x")
          .jump(Opcode.GOTO, "suppressed")
          .at("else")
          .close("r");
    }
  }

  /// `astore t; aload t; astore p; aload t; athrow`, then the `finally` handler: `astore f;
  /// <close>; aload f; athrow`.
  private void javac7To10(Entry body) {
    var rethrow =
        new Shape(body.handler(), Map.of())
            .store("t")
            .load("t")
            .store("p")
            .load("t")
            .is(Opcode.ATHROW);
    int finallyHandler = rethrow.next;
    rethrow.store("f");
    if (!rethrow.matches()
        || !compilers(rethrow, "p", body.handler())
        || entries.stream()
            .noneMatch(entry -> entry.catchesAnything() && entry.handler() == finallyHandler)) {
      return;
    }
    for (var closing : Finally.values()) {
      if (closing.javac && !java10OrBefore) {
        continue;
      }
      var shape = closing.after(rethrow.copy()).load("f").is(Opcode.ATHROW);
      if (shape.matches()) {
        copiesOfFinally(body, finallyHandler, closing, shape);
        return;
      }
    }
  }

  private void copiesOfFinally(Entry body, int finallyHandler, Finally closing, Shape shape) {
    var reason = closing == Finally.KOTLIN ? USE : REASON;
    mark(shape, reason);
    var slots = Map.of("p", shape.slot("p"), "r", shape.slot("r"));
    var ends = new HashSet<>(endsOf(body.handler()));
    ends.addAll(endsOf(finallyHandler));
    ends.forEach(end -> markFirst(reason, closing.after(new Shape(end, slots))));
    // `primary = null` just before the body.
    int start =
        entries.stream()
            .filter(entry -> entry.handler() == body.handler())
            .mapToInt(Entry::start)
            .min()
            .orElse(body.start());
    markFirst(reason, new Shape(start - 2, slots).is(Opcode.ACONST_NULL).store("p"));
  }

  // ECJ

  private void ecj() {
    var primaries = new HashMap<Integer, Integer>();
    var resources = new HashMap<Integer, Integer>();
    for (var entry : entries) {
      if (entry.catchesAnything()) {
        var shape = new Shape(entry.handler(), Map.of()).store("x");
        ecjSuppress(shape);
        if (shape.matches() && compilers(shape, "p", entry.handler())) {
          primaries.put(shape.slot("p"), shape.slot("x"));
          mark(shape, REASON);
          ecjCloseAndThrow(shape.copy())
              .ifPresentOrElse(
                  closed -> resources.put(entry.handler(), closed),
                  () -> markFirst(REASON, shape.copy().load("p").is(Opcode.ATHROW)));
        }
      }
    }
    for (var entry : entries) {
      if (entry.catchesAnything() && !resources.containsKey(entry.handler())) {
        var shape = new Shape(entry.handler(), Map.of()).store("p");
        if (shape.matches() && primaries.containsKey(shape.slot("p"))) {
          ecjCloseAndThrow(shape).ifPresent(closed -> resources.put(entry.handler(), closed));
        }
      }
    }
    resources.forEach(
        (handler, resource) ->
            endsOf(handler)
                .forEach(end -> markFirst(REASON, guardedClose(end, Map.of("r", resource)))));
    primaries.forEach(this::ecjNulls);
  }

  /// `aload p; ifnonnull s; aload x; astore p; goto e; s: aload p; aload x; if_acmpeq e; aload p;
  /// aload x; addSuppressed; e:`, after the `astore x`.
  private static void ecjSuppress(Shape shape) {
    shape
        .load("p")
        .jump(Opcode.IFNONNULL, "s")
        .load("x")
        .store("p")
        .jump(Opcode.GOTO, "e")
        .at("s")
        .load("p")
        .load("x")
        .jump(Opcode.IF_ACMPEQ, "e")
        .suppress("p", "x")
        .at("e");
  }

  /// `aload r; ifnull n; aload r; close(); n: aload p; athrow`: the resource closed, if it is.
  private Optional<Integer> ecjCloseAndThrow(Shape shape) {
    shape.load("r").jump(Opcode.IFNULL, "n").close("r").at("n").load("p").is(Opcode.ATHROW);
    if (!shape.matches()) {
      return Optional.empty();
    }
    mark(shape, REASON);
    return Optional.of(shape.slot("r"));
  }

  /// ECJ's `primary = null; suppressed = null;` before the statement.
  private void ecjNulls(int primary, int suppressed) {
    var slots = Map.of("p", primary, "x", suppressed);
    for (int at = 0; at < positions.size(); at++) {
      markFirst(
          REASON,
          new Shape(at, slots).is(Opcode.ACONST_NULL).store("p").is(Opcode.ACONST_NULL).store("x"));
    }
  }

  // Shared

  /// Whether the slot holding the exception at `handler` is the compiler's: no local variable table
  /// names it there. Code that does the same by hand keeps it in a variable it named, and a class
  /// compiled without names has only the shape to go by.
  private boolean compilers(Shape shape, String exception, int handler) {
    int slot = shape.slot(exception);
    return named.stream()
        .noneMatch(
            local ->
                local.slot() == slot
                    && ordinal(local.startScope()) <= handler + 1
                    && handler < ordinal(local.endScope()));
  }

  /// Where each of the entries with this handler ends.
  private List<Integer> endsOf(int handler) {
    return entries.stream().filter(entry -> entry.handler() == handler).map(Entry::end).toList();
  }

  private void mark(Shape shape, String reason) {
    shape.taken.forEach(ordinal -> found.putIfAbsent(positions.get(ordinal), reason));
  }

  /// The first of `shapes` that matches, marked.
  private void markFirst(String reason, Shape... shapes) {
    for (var shape : shapes) {
      if (shape.matches()) {
        mark(shape, reason);
        return;
      }
    }
  }

  private int ordinal(Label label) {
    return labels.getOrDefault(label, -1);
  }

  private @Nullable Instruction instruction(int ordinal) {
    return ordinal >= 0 && ordinal < positions.size()
        ? (Instruction) code.get(positions.get(ordinal))
        : null;
  }

  private static boolean isClose(InvokeInstruction call) {
    return (call.opcode() == Opcode.INVOKEVIRTUAL || call.opcode() == Opcode.INVOKEINTERFACE)
        && call.name().equalsString("close")
        && call.type().equalsString("()V");
  }

  private static boolean isAddSuppressed(InvokeInstruction call) {
    return call.opcode() == Opcode.INVOKEVIRTUAL
        && call.owner().asInternalName().equals(THROWABLE)
        && call.name().equalsString("addSuppressed")
        && call.type().equalsString("(Ljava/lang/Throwable;)V");
  }

  /// javac 9 and 10's synthetic helper, in the class with the try-with-resources.
  private static boolean isCloseResource(InvokeInstruction call) {
    return call.opcode() == Opcode.INVOKESTATIC
        && call.name().equalsString("$closeResource")
        && call.type().equalsString("(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V");
  }

  private static boolean isCloseFinally(InvokeInstruction call) {
    return call.opcode() == Opcode.INVOKESTATIC
        && KOTLIN_CLOSES.contains(call.owner().asInternalName())
        && call.name().equalsString("closeFinally");
  }

  /// A run of instructions matched one at a time from `next`, each local slot and jump target
  /// named: the first time a name is seen it's bound, and after that it must be the same.
  private final class Shape {
    private int next;
    private boolean failed;
    private final Map<String, Integer> slots;
    private final Map<String, Integer> targets;
    private final List<Integer> taken;

    Shape(int next, Map<String, Integer> slots) {
      this(next, new HashMap<>(slots), new HashMap<>(), new ArrayList<>());
    }

    private Shape(
        int next, Map<String, Integer> slots, Map<String, Integer> targets, List<Integer> taken) {
      this.next = next;
      this.slots = slots;
      this.targets = targets;
      this.taken = taken;
    }

    Shape copy() {
      var copy =
          new Shape(next, new HashMap<>(slots), new HashMap<>(targets), new ArrayList<>(taken));
      copy.failed = failed;
      return copy;
    }

    boolean matches() {
      return !failed;
    }

    int slot(String name) {
      return slots.getOrDefault(name, -1);
    }

    Shape load(String name) {
      return take(
          instruction ->
              instruction instanceof LoadInstruction load
                  && load.typeKind() == TypeKind.REFERENCE
                  && bound(slots, name, load.slot()));
    }

    Shape store(String name) {
      return take(
          instruction ->
              instruction instanceof StoreInstruction store
                  && store.typeKind() == TypeKind.REFERENCE
                  && bound(slots, name, store.slot()));
    }

    Shape is(Opcode opcode) {
      return take(instruction -> instruction.opcode() == opcode);
    }

    /// A jump of this kind (`goto` in either width), to the target of this name.
    Shape jump(Opcode opcode, String target) {
      return take(
          instruction ->
              instruction instanceof BranchInstruction branch
                  && (branch.opcode() == opcode
                      || (opcode == Opcode.GOTO && branch.opcode() == Opcode.GOTO_W))
                  && bound(targets, target, ordinal(branch.target())));
    }

    /// The target of this name is the next instruction.
    Shape at(String target) {
      failed |= !bound(targets, target, next);
      return this;
    }

    Shape call(Predicate<InvokeInstruction> called) {
      return take(
          instruction -> instruction instanceof InvokeInstruction call && called.test(call));
    }

    Shape close(String resource) {
      return load(resource).call(TryWithResources::isClose);
    }

    /// `aload p; aload x; p.addSuppressed(x)`.
    Shape suppress(String primary, String suppressed) {
      return load(primary).load(suppressed).call(TryWithResources::isAddSuppressed);
    }

    private Shape take(Predicate<Instruction> expected) {
      var instruction = failed ? null : instruction(next);
      if (instruction == null || !expected.test(instruction)) {
        failed = true;
      } else {
        taken.add(next++);
      }
      return this;
    }

    private static boolean bound(Map<String, Integer> names, String name, int value) {
      return names.computeIfAbsent(name, _ -> value) == value;
    }
  }
}
