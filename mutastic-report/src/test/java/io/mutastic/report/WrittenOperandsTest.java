package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WrittenOperandsTest {
  @Test
  void anArgumentTheSourceNamesIsNamed() {
    var statement =
        "while ((msg = connection.receiveMessage()) != null"
            + " && !msg.equals(TcpProtocol.EXIT_CODE)) {";

    assertThat(WrittenOperands.of(List.of("msg.equals(\"{}\")"), statement))
        .containsExactly("msg.equals(TcpProtocol.EXIT_CODE)");
  }

  @Test
  void aReceiverTheSourceNamesIsNamed() {
    assertThat(WrittenOperands.of("\"{}\".equals(msg)", "if (TcpProtocol.EXIT_CODE.equals(msg)) {"))
        .isEqualTo("TcpProtocol.EXIT_CODE.equals(msg)");
  }

  @Test
  void aCallSplitOverLinesIsFound() {
    assertThat(
            WrittenOperands.of(
                "text.replace(\"a\", \"b\")",
                "return text.replace(\n    FROM,\n    TO).isEmpty();"))
        .isEqualTo("text.replace(FROM, TO)");
  }

  @Test
  void aStringTheSourceWritesStays() {
    assertThat(
            WrittenOperands.of("head.startsWith(\"ref: \")", "if (head.startsWith(\"ref: \")) {"))
        .isEqualTo("head.startsWith(\"ref: \")");
  }

  /// Two places the call could be, or none: the statement doesn't say which, so the value stays.
  @Test
  void aStringStaysWhenTheStatementDoesNotSayWhichName() {
    assertThat(WrittenOperands.of("a.equals(\"x\")", "if (a.equals(X) || a.equals(Y)) {"))
        .isEqualTo("a.equals(\"x\")");
    assertThat(WrittenOperands.of("a.equals(\"x\")", "if (check(a)) {"))
        .isEqualTo("a.equals(\"x\")");
    assertThat(WrittenOperands.of("\"x\".equals(a)", "if (other().X.equals(a)) {"))
        .isEqualTo("\"x\".equals(a)");
  }

  /// javac joins `PREFIX + ",("` into one string: the comma and bracket in it don't end the
  /// argument.
  @Test
  void anArgumentEndsAtItsOwnCommaOrBracket() {
    assertThat(
            WrittenOperands.of(
                "Objects.equals(\"p,(\", b)", "if (Objects.equals(PREFIX + \",(\", b)) {"))
        .isEqualTo("Objects.equals(PREFIX + \",(\", b)");
  }
}
