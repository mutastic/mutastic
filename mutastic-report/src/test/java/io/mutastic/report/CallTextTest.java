package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// A call found in its statement by name and written again as a template has it: what it's called
/// on and its arguments kept as written, an operation bracketed where it would otherwise change
/// meaning, and nothing where the call can't be told apart or a part left out would still run.
class CallTextTest {
  private static Optional<String> rewritten(String statement, String name, String template) {
    return CallText.find(statement, 0, name).flatMap(call -> call.rewritten(template));
  }

  @Test
  void aCallIsFoundOnJavacsLineOrElseAfterIt() {
    var statement = "    a(b(1),\n        b(2));";

    assertThat(
            CallText.find(statement, 0, "b")
                .flatMap(call -> call.rewritten("{receiver}.c({args})")))
        .contains("    a(c(1),\n        b(2));");
    assertThat(CallText.find("    a(b(1), b(2));", 0, "b")).as("twice on the line").isEmpty();
    assertThat(CallText.find(statement, 12, "b").map(call -> call.argumentCount())).contains(1);
    assertThat(
            CallText.find(statement, 12, "b")
                .flatMap(call -> call.rewritten("{receiver}.c({args})")))
        .contains("    a(b(1),\n        c(2));");
    assertThat(CallText.find("    a(\n        x,\n        b(2));", 0, "b")).isPresent();
    assertThat(CallText.find("    \"b(1)\"; // b(2)", 0, "b")).isEmpty();
    assertThat(CallText.find("    b(1", 0, "b")).isEmpty();
    assertThat(CallText.find("    ab(1);", 0, "b")).isEmpty();
  }

  @Test
  void aRenamedCallKeepsWhatItIsCalledOnAndItsArguments() {
    assertThat(
            rewritten(
                "    ok = list.stream()\n        .anyMatch(p);",
                "anyMatch",
                "{receiver}.allMatch({args})"))
        .contains("    ok = list.stream()\n        .allMatch(p);");
    assertThat(rewritten("    ok = anyMatch (p, q);", "anyMatch", "{receiver}.allMatch({args})"))
        .contains("    ok = allMatch(p, q);");
    assertThat(rewritten("    ok = new Repo(a).find(id);", "find", "{receiver}.get({arg1})"))
        .contains("    ok = new Repo(a).get(id);");
  }

  @Test
  void aTemplateThatNeedsAPartTheCallHasNotIsNotShown() {
    assertThat(rewritten("    find(id);", "find", "{receiver}")).isEmpty();
    assertThat(rewritten("    repo.find(id);", "find", "{arg2}")).isEmpty();
    assertThat(rewritten("    repo.find();", "find", "{arg1}")).isEmpty();
  }

  @Test
  void aPartLeftOutThatStillRunsIsNotShown() {
    assertThat(rewritten("    x = repo().find(id);", "find", "{arg1}")).isEmpty();
    assertThat(rewritten("    x = repo.find(id, i++);", "find", "{arg1}")).isEmpty();
    assertThat(rewritten("    x = repo.find(id, i++);", "find", "{receiver}.get({args})"))
        .contains("    x = repo.get(id, i++);");
    assertThat(rewritten("    x = repo().find(id);", "find", "{receiver}.get({arg1})"))
        .contains("    x = repo().get(id);");
    assertThat(rewritten("    x = repo.find(id, next());", "find", "{arg2}"))
        .contains("    x = next();");
  }

  @Test
  void anOperationIsBracketedWhereAnOperatorWouldTakeItApart() {
    assertThat(rewritten("    return 2 * Math.addExact(a - b, c);", "addExact", "{arg1} + {arg2}"))
        .contains("    return 2 * ((a - b) + c);");
    assertThat(
            rewritten(
                "    long r = Math.multiplyExact(a + b, c);", "multiplyExact", "{arg1} * {arg2}"))
        .contains("    long r = (a + b) * c;");
    assertThat(
            rewritten(
                "    long r = Math.multiplyExact(a, b + c);", "multiplyExact", "{arg1} * {arg2}"))
        .contains("    long r = a * (b + c);");
    assertThat(rewritten("    use(Math.addExact(a, b), c);", "addExact", "{arg1} + {arg2}"))
        .contains("    use(a + b, c);");
    assertThat(rewritten("    use(c, Math.addExact(a, b));", "addExact", "{arg1} + {arg2}"))
        .contains("    use(c, a + b);");
    assertThat(rewritten("    f = x -> Math.addExact(a, b);", "addExact", "{arg1} + {arg2}"))
        .contains("    f = x -> a + b;");
    assertThat(rewritten("    return Math.addExact(a, b) ;", "addExact", "{arg1} + {arg2}"))
        .contains("    return a + b ;");
    assertThat(rewritten("    { Math.addExact(a, b) }", "addExact", "{arg1} + {arg2}"))
        .contains("    { a + b }");
    assertThat(rewritten("Math.addExact(a, b)", "addExact", "{arg1} + {arg2}")).contains("a + b");
    assertThat(rewritten("    x = Math.addExact(a, b).hashCode();", "addExact", "{arg1} + {arg2}"))
        .contains("    x = (a + b).hashCode();");
    assertThat(rewritten("    x = -Math.addExact(a, b);", "addExact", "{arg1} + {arg2}"))
        .contains("    x = -(a + b);");
  }

  @Test
  void anArgumentAloneInATemplatesCallIsNotBracketed() {
    assertThat(rewritten("    x = Math.min(a + b, c);", "min", "Math.max({arg1}, {arg2})"))
        .contains("    x = Math.max(a + b, c);");
    assertThat(rewritten("    x = Math.min(c, a + b);", "min", "Math.max({arg1}, {arg2})"))
        .contains("    x = Math.max(c, a + b);");
    assertThat(rewritten("    x = s.concat(a + b);", "concat", "{receiver} + {arg1}"))
        .contains("    x = s + (a + b);");
  }

  @Test
  void otherArgumentsAreTheOnesTheCallHasInTheOrderGiven() {
    var call = CallText.find("    files.copy(from, to, opts);", 0, "copy").orElseThrow();

    assertThat(call.withArguments(List.of(0, 2, 1))).contains("    files.copy(from, opts, to);");
    assertThat(call.withArguments(List.of(0))).contains("    files.copy(from);");
  }

  @Test
  void aCallIsTakenOutOnlyWhenItIsTheWholeStatement() {
    assertThat(CallText.find("    run();", 0, "run").flatMap(call -> call.removedStatement(false)))
        .contains("");
    assertThat(CallText.find("    x.run(a)", 0, "run").flatMap(call -> call.removedStatement(true)))
        .contains("");
    assertThat(
            CallText.find("    x.run(a) + 1;", 0, "run")
                .flatMap(call -> call.removedStatement(false)))
        .isEmpty();
    assertThat(
            CallText.find("    x.run(a) + 1", 0, "run")
                .flatMap(call -> call.removedStatement(true)))
        .isEmpty();
    assertThat(
            CallText.find("    y = x.run(a);", 0, "run")
                .flatMap(call -> call.removedStatement(false)))
        .isEmpty();
    assertThat(
            CallText.find("    x.run(a, b());", 0, "run")
                .flatMap(call -> call.removedStatement(false)))
        .contains("    b();");
    assertThat(
            CallText.find("    x\n        .run(a, b() + 1);", 0, "run")
                .flatMap(call -> call.removedStatement(false)))
        .isEmpty();
  }

  /// A receiver kept alone takes the call's line break with the call: `raw` and its `;`, not a line
  /// of `;`.
  @Test
  void aReceiverKeptAloneEndsItsStatementWhereItEnds() {
    assertThat(rewritten("    var name = raw\n        .strip();", "strip", "{receiver}"))
        .contains("    var name = raw;");
    assertThat(rewritten("    var name = raw .strip();", "strip", "{receiver}"))
        .contains("    var name = raw;");
    assertThat(rewritten("    var name = raw\n        .strip();", "strip", "{receiver}.trim()"))
        .contains("    var name = raw\n        .trim();");
  }

  @Test
  void aCallAtTheStartOfItsTextIsFoundAndWrittenAgain() {
    var call = CallText.find("copy(from, to);", 0, "copy").orElseThrow();

    assertThat(call.withArguments(List.of(1, 0))).contains("copy(to, from);");
    assertThat(call.removedStatement(false)).contains("");
  }

  @Test
  void anArgumentWithAnOperatorBesideItInATemplatesCallIsBracketed() {
    assertThat(rewritten("    x = Math.min(a + b, c);", "min", "Math.max({arg1} * 2, {arg2})"))
        .contains("    x = Math.max((a + b) * 2, c);");
  }
}
