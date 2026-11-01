package io.mutastic.engine;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;

/// Finds the mutants in a class and applies one at a time, with only `java.lang.classfile`.
final class Mutations {
  private final ClassFile context;

  Mutations(ClassFile context) {
    this.context = context;
  }

  List<Mutant> find(ClassModel model) {
    var mutants = new ArrayList<Mutant>();
    for (MethodModel method : model.methods()) {
      if (!mutable(model, method)) {
        continue;
      }
      method.code().ifPresent(code -> {
        int line = -1;
        int ordinal = 0;
        for (CodeElement element : code) {
          if (element instanceof LineNumber lineNumber) {
            line = lineNumber.line();
          } else if (element instanceof Instruction instruction && isSite(instruction)) {
            for (Operator operator : Operator.values()) {
              if (operator.applies(instruction.opcode())) {
                mutants.add(new Mutant(model.thisClass().asInternalName(), method.methodName().stringValue(),
                    method.methodType().stringValue(), ordinal, line, operator, instruction.opcode().name(),
                    operator.replace(instruction.opcode()).name()));
              }
            }
            ordinal++;
          }
        }
      });
    }
    return mutants;
  }

  /// The class with exactly one instruction changed. `CodeBuilder` regenerates the stack maps.
  byte[] apply(ClassModel model, Mutant mutant) {
    var transform = CodeTransform.ofStateful(() -> new CodeTransform() {
      private int ordinal;

      @Override
      public void accept(CodeBuilder builder, CodeElement element) {
        // A mutant can leave code dead (an "always jump" past a loop body). The ClassFile API
        // patches dead code and shrinks max_locals, but the debug table still names the dead
        // locals, and the JVM rejects the class with a ClassFormatError that verification
        // doesn't catch. Mutants run without local-variable debug info; line numbers stay.
        if (element instanceof LocalVariable || element instanceof LocalVariableType) {
          return;
        }
        if (element instanceof Instruction instruction && isSite(instruction) && ordinal++ == mutant.ordinal()) {
          var branch = (BranchInstruction) instruction;
          builder.with(BranchInstruction.of(mutant.operator().replace(branch.opcode()), branch.target()));
          return;
        }
        builder.with(element);
      }
    });
    return context.transformClass(model, ClassTransform.transformingMethodBodies(
        method -> method.methodName().equalsString(mutant.methodName())
            && method.methodType().equalsString(mutant.descriptor()),
        transform));
  }

  /// Verification errors in a mutated class: none means the JVM will load it.
  List<VerifyError> verify(byte[] bytes) {
    return context.verify(bytes);
  }

  /// Mutation sites: every instruction some operator could change, now or later.
  private static boolean isSite(Instruction instruction) {
    return instruction instanceof OperatorInstruction
        || instruction instanceof BranchInstruction
        || instruction instanceof ReturnInstruction;
  }

  /// Code the user wrote. Constructors and static initialisers are left out (their mutants are
  /// mostly field defaults no test is meant to pin), as is code the compiler wrote: synthetic
  /// methods other than lambda bodies, and an enum's `values()` and `valueOf()`.
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
    boolean enumMethod = name.equals("values") && descriptor.equals("()[" + self)
        || name.equals("valueOf") && descriptor.equals("(Ljava/lang/String;)" + self);
    return !(model.flags().has(AccessFlag.ENUM) && enumMethod);
  }
}
