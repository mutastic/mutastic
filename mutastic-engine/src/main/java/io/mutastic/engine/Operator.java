package io.mutastic.engine;

import static java.lang.classfile.Opcode.IFGE;
import static java.lang.classfile.Opcode.IFGT;
import static java.lang.classfile.Opcode.IFLE;
import static java.lang.classfile.Opcode.IFLT;
import static java.lang.classfile.Opcode.IF_ICMPGE;
import static java.lang.classfile.Opcode.IF_ICMPGT;
import static java.lang.classfile.Opcode.IF_ICMPLE;
import static java.lang.classfile.Opcode.IF_ICMPLT;

import java.lang.classfile.Opcode;
import java.util.Map;

/// The mutation operators, and what each instruction can become.
public enum Operator {
  /// `<` becomes `<=` and the other way round: the off-by-one at the edge of a range, which tests
  /// that only check values far from the edge never notice.
  BOUNDARY(Map.of(
      IF_ICMPLT, IF_ICMPLE, IF_ICMPLE, IF_ICMPLT, IF_ICMPGT, IF_ICMPGE, IF_ICMPGE, IF_ICMPGT,
      IFLT, IFLE, IFLE, IFLT, IFGT, IFGE, IFGE, IFGT));

  private final Map<Opcode, Opcode> replacements;

  Operator(Map<Opcode, Opcode> replacements) {
    this.replacements = replacements;
  }

  boolean applies(Opcode opcode) {
    return replacements.containsKey(opcode);
  }

  Opcode replace(Opcode opcode) {
    return replacements.get(opcode);
  }

  /// `mutastic/boundary`: how reports and rules name it.
  public String id() {
    return "mutastic/" + name().toLowerCase().replace('_', '-');
  }
}
