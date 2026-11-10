package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// Reading source as text for a card's changed lines: strings and comments covered over where they
/// are, and where an expression starts, so a change is made in code and never in a literal.
class CodeTest {
  @Test
  void stringsCharactersAndCommentsAreCoveredWhereTheyAre() {
    assertThat(Code.masked("a(\"x\\\"y\", 'z', '\\'') // c(d)"))
        .isEqualTo("a(______, ___, ____)" + " ".repeat(8));
    assertThat(Code.masked("a /* b(c) */ + d")).isEqualTo("a            + d");
    assertThat(Code.masked("a // b\nc")).isEqualTo("a     \nc");
    assertThat(Code.masked("a /* b\nc */ d")).isEqualTo("a     \n     d");
    assertThat(Code.masked("a /* b")).isEqualTo("a     ");
    assertThat(Code.masked("a / b * c")).isEqualTo("a / b * c");
    assertThat(Code.masked("a /*/ b */ c"))
        .as("its opening `*` closes nothing")
        .isEqualTo("a" + " ".repeat(10) + "c");
  }

  @Test
  void aLiteralThatDoesNotCloseEndsAtItsLine() {
    assertThat(Code.masked("\"ab\ncd")).isEqualTo("___\ncd");
    assertThat(Code.masked("x(\"ab")).isEqualTo("x(___");
  }

  @Test
  void bracketsCloseAtTheirPair() {
    assertThat(Code.closing("f(a(b), [c]) + d", 1)).isEqualTo(11);
    assertThat(Code.closing("f(a(b)", 1)).isEqualTo(-1);
  }

  @Test
  void anExpressionStartsAtTheStartOfWhatAMemberIsSelectedFrom() {
    var chain = "x = repo.find(id).orElse(y)";
    assertThat(Code.expressionStart(chain, chain.lastIndexOf('.'))).isEqualTo(4);

    var made = "x = new Repo(a).find(id)";
    assertThat(Code.expressionStart(made, made.lastIndexOf('.'))).isEqualTo(4);

    var first = "new Repo(a).find(id)";
    assertThat(Code.expressionStart(first, first.indexOf(".find"))).isEqualTo(0);

    var indexed = "x = a[i].b";
    assertThat(Code.expressionStart(indexed, indexed.indexOf(".b"))).isEqualTo(4);

    var bracketed = "x = (a + b).c()";
    assertThat(Code.expressionStart(bracketed, bracketed.indexOf(".c"))).isEqualTo(4);

    var split = "x = repo\n    .find(id)\n    .orElse(y)";
    assertThat(Code.expressionStart(split, split.lastIndexOf('.'))).isEqualTo(4);

    var renamed = "x = renew(a).b";
    assertThat(Code.expressionStart(renamed, renamed.indexOf(".b"))).isEqualTo(4);

    var unopened = "x = a).b";
    assertThat(Code.expressionStart(unopened, unopened.indexOf(".b"))).isEqualTo(6);
  }

  @Test
  void anOperationIsASpaceOutsideBracketsOrASignOrCastInFront() {
    assertThat(Code.isOperation("a + b")).isTrue();
    assertThat(Code.isOperation("-a")).isTrue();
    assertThat(Code.isOperation("(int) a")).isTrue();
    assertThat(Code.isOperation("(a)")).isTrue();
    assertThat(Code.isOperation("f(a, b)")).isFalse();
    assertThat(Code.isOperation("a[i - 1]")).isFalse();
    assertThat(Code.isOperation("\"a b\"")).isFalse();
    assertThat(Code.isOperation("a")).isFalse();
  }

  @Test
  void codeDoesSomethingWhenItCallsMakesOrAssigns() {
    assertThat(Code.doesSomething("a.b()")).isTrue();
    assertThat(Code.doesSomething("new X")).isTrue();
    assertThat(Code.doesSomething("i++")).isTrue();
    assertThat(Code.doesSomething("a = b")).isTrue();
    assertThat(Code.doesSomething("list.get(0)")).isTrue();
    assertThat(Code.doesSomething("x -> x.run()")).isFalse();
    assertThat(Code.doesSomething("(a, b) -> a.add(b)")).isFalse();
    assertThat(Code.doesSomething("Type::name")).isFalse();
    assertThat(Code.doesSomething("a == b")).isFalse();
    assertThat(Code.doesSomething("a <= b")).isFalse();
    assertThat(Code.doesSomething("\"call()\"")).isFalse();
    assertThat(Code.doesSomething("a[i] + 1")).isFalse();
  }

  /// What can be a statement of its own: a call, a new object, an assignment, `++` or `--`, without
  /// the brackets around it all.
  @Test
  void aStatementIsACallANewObjectOrAWrite() {
    assertThat(Code.asStatement(" (next()) ")).contains("next()");
    assertThat(Code.asStatement("\n  /* the sum */ next() // now")).contains("next()");
    assertThat(Code.asStatement("items\n    .next()")).contains("items\n    .next()");
    assertThat(Code.asStatement("new Total(price)")).contains("new Total(price)");
    assertThat(Code.asStatement("count++")).contains("count++");
    assertThat(Code.asStatement("--count")).contains("--count");
    assertThat(Code.asStatement("this.total = next()")).contains("this.total = next()");
    assertThat(Code.asStatement("totals[i] += 2")).contains("totals[i] += 2");
    assertThat(Code.asStatement("a == next()")).isEmpty();
    assertThat(Code.asStatement("a + next()")).isEmpty();
    assertThat(Code.asStatement("!next()")).isEmpty();
    assertThat(Code.asStatement("(a) + next()")).isEmpty();
    assertThat(Code.asStatement("next().value")).isEmpty();
    assertThat(Code.asStatement("a ? next() : b()")).isEmpty();
    assertThat(Code.asStatement("(items).next()")).contains("(items).next()");
    assertThat(Code.asStatement("x")).as("a name alone").isEmpty();
    assertThat(Code.asStatement("   ")).isEmpty();
    assertThat(Code.asStatement("/* only a comment */")).isEmpty();
    assertThat(Code.asStatement("a+next()")).isEmpty();
    assertThat(Code.asStatement("a next()")).isEmpty();
  }
}
