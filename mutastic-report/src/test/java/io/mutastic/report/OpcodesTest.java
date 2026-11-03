package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// The instructions `explain` describes, in words: every kind it knows, and none for one it
/// doesn't.
class OpcodesTest {
  @Test
  void jumps() {
    assertThat(Opcodes.describe("IF_ICMPLT"))
        .hasValue("jump if the first int is less than the second");
    assertThat(Opcodes.describe("IF_ICMPGE"))
        .hasValue("jump if the first int is greater than or equal to the second");
    assertThat(Opcodes.describe("IF_ACMPEQ")).hasValue("jump if both are the same object");
    assertThat(Opcodes.describe("IF_ACMPNE")).hasValue("jump if they're different objects");
    assertThat(Opcodes.describe("IFNULL")).hasValue("jump if it's null");
    assertThat(Opcodes.describe("IFNONNULL")).hasValue("jump if it isn't null");
    assertThat(Opcodes.describe("IFEQ"))
        .hasValue("jump if the int (or the comparison's result, or a boolean) is equal to zero");
    assertThat(Opcodes.describe("IFGT"))
        .hasValueSatisfying(words -> assertThat(words).endsWith("greater than zero"));
    assertThat(Opcodes.describe("IF_ICMPXX")).isEmpty();
    assertThat(Opcodes.describe("IFXX")).isEmpty();
  }

  @Test
  void removedConditions() {
    assertThat(Opcodes.describe("always jump"))
        .hasValue("jump whatever the condition: the code it skips never runs");
    assertThat(Opcodes.describe("never jump"))
        .hasValue("never jump: the code the jump would skip always runs");
  }

  @Test
  void arithmeticReturnsAndCalls() {
    assertThat(Opcodes.describe("IADD")).hasValue("add two ints");
    assertThat(Opcodes.describe("LMUL")).hasValue("multiply two longs");
    assertThat(Opcodes.describe("DDIV")).hasValue("divide two doubles");
    assertThat(Opcodes.describe("XADD")).isEmpty();
    assertThat(Opcodes.describe("IRETURN"))
        .hasValue("return the value on the stack (one of the ints)");
    assertThat(Opcodes.describe("ARETURN"))
        .hasValue("return the value on the stack (one of the references)");
    assertThat(Opcodes.describe("XRETURN")).isEmpty();
    assertThat(Opcodes.describe("return default"))
        .hasValue("return the type's default (0, false or null) instead");
    assertThat(Opcodes.describe("remove call"))
        .hasValue("nothing: the call is gone, and so is whatever it would have done");
    assertThat(Opcodes.describe("INVOKEVIRTUAL com/acme/Audit.record(Ljava/lang/String;)V"))
        .hasValue("call Audit.record()");
    assertThat(Opcodes.describe("INVOKESTATIC")).isEmpty();
    assertThat(Opcodes.describe("NOP")).isEmpty();
    assertThat(Opcodes.describe("GOTO")).isEmpty();
  }

  @Test
  void symbolsForIntComparisons() {
    assertThat(Opcodes.symbol("IF_ICMPLE")).hasValue("<=");
    assertThat(Opcodes.symbol("IF_ICMPNE")).hasValue("!=");
    assertThat(Opcodes.symbol("IFEQ")).isEmpty();
    assertThat(Opcodes.symbol("IF_ICMPXX")).isEmpty();
  }
}
