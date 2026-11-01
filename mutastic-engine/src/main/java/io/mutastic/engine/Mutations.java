package io.mutastic.engine;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Finds the mutants in a class and applies one at a time, with only `java.lang.classfile`.
final class Mutations {
  private static final Set<Opcode> DEFAULT_CONSTANTS =
      Set.of(
          Opcode.ICONST_0, Opcode.LCONST_0, Opcode.FCONST_0, Opcode.DCONST_0, Opcode.ACONST_NULL);

  /// Calls whose only effect is output: removing one changes what a person reads, not what the code
  /// does, so no test is expected to notice.
  private static final Set<String> OUTPUT_OWNERS =
      Set.of(
          "org/slf4j/Logger",
          "java/util/logging/Logger",
          "java/lang/System$Logger",
          "org/apache/logging/log4j/Logger",
          "org/apache/commons/logging/Log",
          "java/io/PrintStream");

  private final ClassFile context;

  Mutations(ClassFile context) {
    this.context = context;
  }

  List<Mutant> find(ClassModel model) {
    var mutants = new ArrayList<Mutant>();
    for (MethodModel method : model.methods()) {
      if (mutable(model, method)) {
        method
            .code()
            .ifPresent(
                code -> {
                  var found = new ArrayList<Mutant>();
                  findIn(model, method, code.elementList(), found);
                  mutants.addAll(mergeFinallyCopies(found, code));
                });
      }
    }
    return mutants;
  }

  private static void findIn(
      ClassModel model, MethodModel method, List<CodeElement> code, List<Mutant> mutants) {
    var tryWithResources = tryWithResources(code);
    var operands = method.code().map(Operands::new).orElseThrow();
    var stringSwitchLines = new HashSet<Integer>();
    int line = -1;
    int ordinal = 0;
    int callOrdinal = 0;
    Instruction previous = null;
    for (CodeElement element : code) {
      switch (element) {
        case LineNumber lineNumber -> line = lineNumber.line();
        case LabelTarget _ -> {
          previous = null; // a jump lands here: the instruction before isn't the only way in
          operands.jumpTarget();
        }
        case Instruction instruction -> {
          var workedOn = operands.of(instruction);
          if (isSwitch(instruction) && isCall(previous, "java/lang/String", "hashCode")) {
            stringSwitchLines.add(line); // javac's switch on a String: hashCode, then equals
          }
          if (isSite(instruction) || instruction instanceof InvokeInstruction) {
            boolean call = instruction instanceof InvokeInstruction;
            for (Operator operator : Operator.values()) {
              if (operator.onCalls() == call && operator.applies(instruction)) {
                var skipped =
                    tryWithResources.contains(instruction)
                        ? Optional.of("generated: closes a try-with-resources resource")
                        : skipReason(operator, instruction, previous, line, stringSwitchLines);
                mutants.add(
                    new Mutant(
                        model.thisClass().asInternalName(),
                        method.methodName().stringValue(),
                        method.methodType().stringValue(),
                        call ? callOrdinal : ordinal,
                        line,
                        operator,
                        describe(instruction),
                        operator.replacement(instruction),
                        skipped,
                        List.of(),
                        workedOn));
              }
            }
            if (call) {
              callOrdinal++;
            } else {
              ordinal++;
            }
          }
          previous = instruction;
        }
        default -> {}
      }
    }
  }

  /// The instructions javac writes to close a try-with-resources resource: on the normal path
  /// `aload r; ifnull; aload r; close()`, and again in a `catch Throwable` handler that adds a
  /// failure to close as suppressed. No test can see them, so their mutants would all be missed.
  /// Found only in a method that calls `Throwable.addSuppressed`: the `addSuppressed` calls, each
  /// `close()` on a local slot javac guards with `ifnull` or closes on both paths, and those
  /// `ifnull` guards.
  private static java.util.Set<Instruction> tryWithResources(List<CodeElement> code) {
    var instructions =
        code.stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
    var generated =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Instruction, Boolean>());
    if (instructions.stream()
        .noneMatch(instruction -> isCall(instruction, "java/lang/Throwable", "addSuppressed"))) {
      return generated;
    }
    var closesBySlot = new java.util.HashMap<Integer, List<Integer>>();
    var guardedSlots = new HashSet<Integer>();
    for (int at = 1; at < instructions.size(); at++) {
      var instruction = instructions.get(at);
      if (isCall(instruction, "java/lang/Throwable", "addSuppressed")) {
        generated.add(instruction);
      }
      if (isClose(instruction) && instructions.get(at - 1) instanceof LoadInstruction load) {
        closesBySlot.computeIfAbsent(load.slot(), slot -> new ArrayList<>()).add(at);
        if (at >= 3
            && instructions.get(at - 2).opcode() == Opcode.IFNULL
            && instructions.get(at - 3) instanceof LoadInstruction guarded
            && guarded.slot() == load.slot()) {
          guardedSlots.add(load.slot());
          generated.add(instructions.get(at - 2));
        }
      }
    }
    closesBySlot.forEach(
        (slot, closes) -> {
          if (closes.size() >= 2 || guardedSlots.contains(slot)) {
            closes.forEach(at -> generated.add(instructions.get(at)));
          }
        });
    return generated;
  }

  private static boolean isClose(Instruction instruction) {
    return instruction instanceof InvokeInstruction call
        && call.name().equalsString("close")
        && call.type().equalsString("()V");
  }

  /// The class with exactly one instruction changed. `CodeBuilder` regenerates the stack maps.
  byte[] apply(ClassModel model, Mutant mutant) {
    var transform =
        CodeTransform.ofStateful(
            () ->
                new CodeTransform() {
                  private int ordinal;
                  private int callOrdinal;

                  @Override
                  public void accept(CodeBuilder builder, CodeElement element) {
                    // A mutant can leave code dead (a negated loop condition). The ClassFile API
                    // patches
                    // dead code and shrinks max_locals, but the debug table still names the dead
                    // locals,
                    // and the JVM rejects the class with a ClassFormatError that verification
                    // doesn't
                    // catch. Mutants run without local-variable debug info; line numbers stay.
                    if (element instanceof LocalVariable || element instanceof LocalVariableType) {
                      return;
                    }
                    if (element instanceof Instruction instruction) {
                      boolean target =
                          instruction instanceof InvokeInstruction
                              ? mutant.operator().onCalls() && mutant.changes(callOrdinal++)
                              : isSite(instruction)
                                  && !mutant.operator().onCalls()
                                  && mutant.changes(ordinal++);
                      if (target) {
                        emit(builder, instruction, mutant.operator());
                        return;
                      }
                    }
                    builder.with(element);
                  }
                });
    return context.transformClass(
        model,
        ClassTransform.transformingMethodBodies(
            method ->
                method.methodName().equalsString(mutant.methodName())
                    && method.methodType().equalsString(mutant.descriptor()),
            transform));
  }

  /// The whole-number constants loaded just before the mutant's site, `100` in `total >= 100`: the
  /// values where its change shows, if anywhere.
  List<Long> constantsNear(ClassModel model, Mutant mutant) {
    var constants = new ArrayList<Long>();
    for (MethodModel method : model.methods()) {
      if (!method.methodName().equalsString(mutant.methodName())
          || !method.methodType().equalsString(mutant.descriptor())) {
        continue;
      }
      var recent = new java.util.ArrayDeque<Instruction>();
      int ordinal = 0;
      int callOrdinal = 0;
      for (CodeElement element : method.code().orElseThrow()) {
        if (!(element instanceof Instruction instruction)) {
          continue;
        }
        boolean here =
            instruction instanceof InvokeInstruction
                ? mutant.operator().onCalls() && mutant.changes(callOrdinal++)
                : isSite(instruction) && !mutant.operator().onCalls() && mutant.changes(ordinal++);
        if (here) {
          for (Instruction before : recent) {
            if (before instanceof ConstantInstruction constant
                && constant.constantValue() instanceof Number number
                && (number instanceof Integer || number instanceof Long)) {
              constants.add(number.longValue());
            }
          }
          return constants;
        }
        recent.addLast(instruction);
        if (recent.size() > 2) {
          recent.removeFirst();
        }
      }
    }
    return constants;
  }

  /// Verification errors in a mutated class: none means the JVM will load it.
  List<VerifyError> verify(byte[] bytes) {
    return context.verify(bytes);
  }

  private static void emit(CodeBuilder builder, Instruction instruction, Operator operator) {
    switch (instruction) {
      case BranchInstruction branch ->
          builder.with(BranchInstruction.of(operator.replace(branch.opcode()), branch.target()));
      case OperatorInstruction arithmetic ->
          builder.with(OperatorInstruction.of(operator.replace(arithmetic.opcode())));
      case ReturnInstruction returning -> {
        var kind = returning.typeKind();
        pop(builder, kind);
        switch (kind) {
          case LONG -> builder.lconst_0();
          case FLOAT -> builder.fconst_0();
          case DOUBLE -> builder.dconst_0();
          case REFERENCE -> builder.aconst_null();
          default -> builder.iconst_0(); // int, and the boolean, char, short and byte it carries
        }
        builder.return_(kind);
      }
      case InvokeInstruction call -> {
        // The arguments, last first, then the receiver: the stack as if the call had run.
        for (var parameter : call.typeSymbol().parameterList().reversed()) {
          pop(builder, TypeKind.from(parameter));
        }
        if (call.opcode() != Opcode.INVOKESTATIC) {
          builder.pop();
        }
      }
      default -> throw new IllegalStateException("not a mutation site: " + instruction);
    }
  }

  private static void pop(CodeBuilder builder, TypeKind kind) {
    if (kind.slotSize() == 2) {
      builder.pop2();
    } else {
      builder.pop();
    }
  }

  /// One mutant for each change javac copied with a `finally` block.
  ///
  /// javac compiles `finally` by copying its code onto every way out of the `try`: once on the
  /// normal path, and once in a catch-anything handler for exceptions. Mutating one copy alone is a
  /// different, weaker change than the source suggests (removing only the exception path's
  /// `unlock()` goes unnoticed unless a test throws), and counts one source change as several
  /// mutants. So the same change on the same line, with a copy on each side of a catch-anything
  /// handler's start, becomes one mutant that changes every copy.
  private static List<Mutant> mergeFinallyCopies(
      List<Mutant> found, java.lang.classfile.CodeModel code) {
    var handlerStarts =
        code.exceptionHandlers().stream()
            .filter(handler -> handler.catchType().isEmpty())
            .map(java.lang.classfile.instruction.ExceptionCatch::handler)
            .collect(java.util.stream.Collectors.toSet());
    if (handlerStarts.isEmpty()) {
      return found;
    }
    // Where each catch-anything handler starts, counted in each kind of site.
    var siteStarts = new ArrayList<Integer>();
    var callStarts = new ArrayList<Integer>();
    int ordinal = 0;
    int callOrdinal = 0;
    for (CodeElement element : code) {
      if (element instanceof LabelTarget target && handlerStarts.contains(target.label())) {
        siteStarts.add(ordinal);
        callStarts.add(callOrdinal);
      } else if (element instanceof InvokeInstruction) {
        callOrdinal++;
      } else if (element instanceof Instruction instruction && isSite(instruction)) {
        ordinal++;
      }
    }
    var groups = new java.util.LinkedHashMap<List<Object>, List<Mutant>>();
    for (Mutant mutant : found) {
      groups
          .computeIfAbsent(
              List.of(mutant.line(), mutant.operator(), mutant.original(), mutant.replacement()),
              key -> new ArrayList<>())
          .add(mutant);
    }
    var merged = new ArrayList<Mutant>();
    for (Mutant mutant : found) {
      var group =
          groups.get(
              List.of(mutant.line(), mutant.operator(), mutant.original(), mutant.replacement()));
      var starts = mutant.operator().onCalls() ? callStarts : siteStarts;
      boolean straddles =
          group.size() > 1
              && starts.stream()
                  .anyMatch(
                      start ->
                          group.stream().anyMatch(copy -> copy.ordinal() < start)
                              && group.stream().anyMatch(copy -> copy.ordinal() >= start));
      if (!straddles) {
        merged.add(mutant);
      } else if (mutant.equals(
          group.getFirst())) { // the later copies go into this one, in its place
        merged.add(
            new Mutant(
                mutant.className(),
                mutant.methodName(),
                mutant.descriptor(),
                mutant.ordinal(),
                mutant.line(),
                mutant.operator(),
                mutant.original(),
                mutant.replacement(),
                mutant.skipped(),
                group.stream().skip(1).map(Mutant::ordinal).toList(),
                mutant.operands()));
      }
    }
    return merged;
  }

  /// Why a mutant shouldn't run, if it shouldn't: the reason's first word is its kind.
  private static Optional<String> skipReason(
      Operator operator,
      Instruction instruction,
      Instruction previous,
      int line,
      Set<Integer> stringSwitchLines) {
    if (operator == Operator.RETURN_DEFAULT
        && previous instanceof ConstantInstruction constant
        && DEFAULT_CONSTANTS.contains(constant.opcode())) {
      return Optional.of("equivalent: already returns the default");
    }
    if (instruction instanceof BranchInstruction
        && stringSwitchLines.contains(line)
        && isCall(previous, "java/lang/String", "equals")) {
      return Optional.of("generated: part of a switch on a String");
    }
    if (instruction instanceof InvokeInstruction call
        && OUTPUT_OWNERS.contains(call.owner().asInternalName())) {
      return Optional.of("arid: only writes output");
    }
    return Optional.empty();
  }

  /// `IF_ICMPLT`, or for a call `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`.
  private static String describe(Instruction instruction) {
    return instruction instanceof InvokeInstruction call
        ? "%s %s.%s%s"
            .formatted(
                call.opcode().name(),
                call.owner().asInternalName(),
                call.name().stringValue(),
                call.type().stringValue())
        : instruction.opcode().name();
  }

  /// Instruction sites: every arithmetic, branch and return instruction, whether or not an operator
  /// applies to it today.
  private static boolean isSite(Instruction instruction) {
    return instruction instanceof OperatorInstruction
        || instruction instanceof BranchInstruction
        || instruction instanceof ReturnInstruction;
  }

  private static boolean isSwitch(Instruction instruction) {
    return instruction.opcode() == Opcode.LOOKUPSWITCH
        || instruction.opcode() == Opcode.TABLESWITCH;
  }

  private static boolean isCall(Instruction instruction, String owner, String name) {
    return instruction instanceof InvokeInstruction call
        && call.owner().asInternalName().equals(owner)
        && call.name().equalsString(name);
  }

  /// Code the user wrote. Constructors and static initialisers are left out (their mutants are
  /// mostly field defaults no test is meant to pin), as is code the compiler wrote: synthetic
  /// methods other than lambda bodies, an enum's `values()` and `valueOf()`, and a record's
  /// `toString()`, `hashCode()` and `equals()`.
  private static boolean mutable(ClassModel model, MethodModel method) {
    var name = method.methodName().stringValue();
    if (name.startsWith("<")) {
      return false;
    }
    if (method.flags().has(AccessFlag.SYNTHETIC) && !name.startsWith("lambda$")) {
      return false;
    }
    var self = "L" + model.thisClass().asInternalName() + ";";
    var descriptor = method.methodType().stringValue();
    boolean enumMethod =
        (name.equals("values") && descriptor.equals("()[" + self))
            || (name.equals("valueOf") && descriptor.equals("(Ljava/lang/String;)" + self));
    if (model.flags().has(AccessFlag.ENUM) && enumMethod) {
      return false;
    }
    return method.code().stream()
        .flatMap(code -> code.elementStream())
        .noneMatch(
            element ->
                element instanceof InvokeDynamicInstruction indy
                    && indy.bootstrapMethod()
                        .owner()
                        .descriptorString()
                        .equals("Ljava/lang/runtime/ObjectMethods;"));
  }
}
