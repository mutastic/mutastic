package io.mutastic.engine;

import io.mutastic.api.Mutator.Change;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ConstantDescs;
import java.util.List;

/// What `mutastic/replace-body` makes of a method: its whole code replaced by a return of the value
/// [ReturnValues] chooses, or by nothing, where it returns nothing. So a `boolean` gets both
/// values, as always `true` and always `false` are different gaps and neither shows the other, and
/// anything else gets one.
///
/// Code that's only that return already has nothing to lose: `return null;` is equivalent, and a
/// method with no code at all has no change.
final class MethodBodies {
  static final String EMPTIED = "body emptied";
  static final String BODY = "body ";

  private MethodBodies() {}

  static List<Change> changes(ClassModel model, MethodModel method, List<CodeElement> code) {
    var instructions = code.stream().filter(Instruction.class::isInstance).toList();
    var returned = method.methodTypeSymbol().returnType();
    if (returned.equals(ConstantDescs.CD_void)) {
      return instructions.size() == 1 ? List.of() : List.of(Change.of(EMPTIED));
    }
    boolean onlyAValue = instructions.size() <= 2;
    return ReturnValues.changes(model, returned, code, onlyReturn(code)).stream()
        .map(
            change ->
                onlyAValue
                    ? new Change(BODY + change.label(), change.skipped())
                    : Change.of(BODY + change.label()))
        .toList();
  }

  /// Where the method's one return is; with several there's no one value it gives today, and `0`
  /// has nothing before it to read.
  private static int onlyReturn(List<CodeElement> code) {
    int found = 0;
    int returns = 0;
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof ReturnInstruction) {
        found = at;
        returns++;
      }
    }
    return returns == 1 ? found : 0;
  }

  static void write(CodeBuilder builder, java.lang.constant.ClassDesc returned, String label) {
    if (label.equals(EMPTIED)) {
      builder.return_();
    } else {
      ReturnValues.writeBody(builder, returned, label.substring(BODY.length()));
    }
  }
}
