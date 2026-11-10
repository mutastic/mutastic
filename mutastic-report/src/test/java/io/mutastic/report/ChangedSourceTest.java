package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The `+` line a card shows: each operator's change written into the statement where the source
/// has it exactly once, and nothing where it doesn't, so a card never shows a line that isn't the
/// mutant's. Statements as text, with the engine's labels, so each case is cheap.
class ChangedSourceTest {
  private static Mutant mutant(
      BuiltIn operator, String original, String replacement, String... operands) {
    return new Mutant(
        "com/example/Shop",
        "total",
        "(I)I",
        0,
        1,
        operator,
        original,
        replacement,
        Optional.empty(),
        List.of(),
        List.of(operands));
  }

  private static Optional<String> changed(String statement, Mutant mutant) {
    return new ChangedSource(statement, 0, false).changed(mutant, Advice.of(mutant));
  }

  private static Optional<String> kotlin(String statement, Mutant mutant) {
    return new ChangedSource(statement, 0, true).changed(mutant, Advice.of(mutant));
  }

  @Test
  void aConstantChangesWhereItIsAWholeNumberInCode() {
    var constant = mutant(BuiltIn.CONSTANT_VALUE, "BIPUSH", "constant 100 to 101");

    assertThat(changed("    check(100, \"100 left\", 1100, 100L);", constant))
        .contains("    check(101, \"100 left\", 1100, 100L);");
    assertThat(changed("    check(100, 100);", constant)).as("there twice").isEmpty();
    assertThat(changed("    check(LIMIT);", constant)).as("a name the source gives").isEmpty();
  }

  @Test
  void aConstantIsFoundFromJavacsLine() {
    var constant = mutant(BuiltIn.CONSTANT_VALUE, "BIPUSH", "constant 100 to 101");
    var statement = "    check(100,\n        100);";

    assertThat(new ChangedSource(statement, 15, false).changed(constant, Advice.of(constant)))
        .contains("    check(100,\n        101);");
  }

  @Test
  void aForcedConditionIsItsValueAndANegatedValueIsSimplified() {
    var never = mutant(BuiltIn.REMOVE_CONDITIONAL, "IFEQ", "never jump", "count", "0");
    var always = mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "count", "0");

    assertThat(changed("    return ready && count != 0;", never))
        .contains("    return ready && true;");
    assertThat(changed("    if (!(count == 0)) {", always)).contains("    if (true) {");
    assertThat(changed("    if (count != 0 || count != 0) {", never)).isEmpty();
  }

  @Test
  void simplifyingLeavesANameThatStartsWithAValueAlone() {
    assertThat(ChangedSource.simplified("a(!true, ! ( false ), !trueish, !false)"))
        .isEqualTo("a(false, true, !trueish, true)");
    assertThat(ChangedSource.simplified("a(b)")).isEqualTo("a(b)");
  }

  @Test
  void aConditionOnANamedConstantIsItsValue() {
    var never = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "count", "50");
    var flipped = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "50", "count");

    assertThat(changed("    if (count >= BULK_FROM) {", never)).contains("    if (true) {");
    assertThat(changed("    if (count >= Limits.BULK_FROM) {", never)).contains("    if (true) {");
    assertThat(changed("    if (BULK_FROM >= count) {", flipped)).contains("    if (true) {");
    assertThat(changed("    if (count >= A || count >= B) {", never)).as("twice").isEmpty();
    assertThat(changed("    if (count >= limit()) {", never)).as("a call, not a name").isEmpty();
    assertThat(changed("    if (xcount >= LIMIT) {", never)).as("another name").isEmpty();
  }

  @Test
  void aConditionOfTwoNumbersOrTwoNamesIsNoNamedConstant() {
    var numbers = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "1", "50");
    var names = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "a", "b");

    assertThat(changed("    if (ONE >= LIMIT) {", numbers)).isEmpty();
    assertThat(changed("    if (a >= LIMIT) {", names)).isEmpty();
  }

  @Test
  void aConditionAfterJavacsLineIsNotOneBeforeIt() {
    var never = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "count", "50");
    var statement = "    if (count >= LIMIT\n        && count >= BULK) {";

    assertThat(new ChangedSource(statement, 23, false).changed(never, Advice.of(never)))
        .contains("    if (count >= LIMIT\n        && true) {");
  }

  @Test
  void aRemovedCallIsTheStatementGoneWhenTheCallIsAllItDoes() {
    var removed =
        mutant(
            BuiltIn.REMOVE_VOID_CALL,
            "INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V",
            "remove call");

    assertThat(changed("    audit.record(name);", removed)).contains("");
    assertThat(kotlin("    audit.record(name)", removed)).contains("");
    assertThat(changed("    audit.record(name)", removed)).as("no `;` in Java").isEmpty();
    assertThat(changed("    audit().record(name);", removed)).as("audit() still runs").isEmpty();
    assertThat(changed("    audit.record(count++);", removed)).as("count++ still runs").isEmpty();
    assertThat(changed("    if (on) audit.record(name);", removed)).isEmpty();
  }

  @Test
  void aCallLeftOutLeavesWhatItWasCalledOnOrGiven() {
    var receiver =
        mutant(
            BuiltIn.OMIT_CALL,
            "INVOKEVIRTUAL java/lang/String.strip()Ljava/lang/String;",
            "keep receiver");
    var argument =
        mutant(BuiltIn.OMIT_CALL, "INVOKESTATIC java/lang/Math.abs(I)I", "keep argument");

    assertThat(changed("    var name = raw.strip();", receiver)).contains("    var name = raw;");
    assertThat(changed("    var n = Math.abs(a - b) * 2;", argument))
        .contains("    var n = (a - b) * 2;");
    assertThat(changed("    var n = Math.abs(a - b);", argument)).contains("    var n = a - b;");
    assertThat(changed("    return 1 + Math.abs(a);", argument)).contains("    return 1 + a;");
    assertThat(changed("    use(Math.abs(a - b), c);", argument)).contains("    use(a - b, c);");
  }

  @Test
  void swappedArgumentsAreTheLastTwoTheOtherWayRound() {
    var swap =
        mutant(
            BuiltIn.SWAP_ARGUMENTS,
            "INVOKESTATIC com/example/Files.copy(II)V",
            "swap last two arguments");

    assertThat(changed("    copy(from, to);", swap)).contains("    copy(to, from);");
    assertThat(changed("    files.copy(from, to);", swap)).contains("    files.copy(to, from);");
    assertThat(changed("    copy(a, b, c);", swap)).contains("    copy(a, c, b);");
    assertThat(changed("    copy(a);", swap)).isEmpty();
    assertThat(changed("    copy();", swap)).isEmpty();
  }

  @Test
  void aDroppedVarargIsTheLastValueGone() {
    var drop =
        mutant(
            BuiltIn.DROP_VARARG,
            "INVOKESTATIC com/example/Log.log(Ljava/lang/String;[Ljava/lang/Object;)V",
            "drop last element");

    assertThat(changed("    log(\"%s %s\", a, b);", drop)).contains("    log(\"%s %s\", a);");
    assertThat(changed("    log(new String[] {\"a\", \"b, c\"});", drop))
        .contains("    log(new String[] {\"a\"});");
    assertThat(changed("    log(new String[] {only});", drop))
        .contains("    log(new String[] {});");
    assertThat(changed("    log(new String[] {a, next()});", drop)).isEmpty();
    assertThat(changed("    log(new String[] {});", drop)).isEmpty();
    assertThat(changed("    log();", drop)).isEmpty();
  }

  @Test
  void aChangedReturnIsTheWholeReturnWithTheMutantsValue() {
    var zero = mutant(BuiltIn.RETURN_VALUE, "IRETURN", "return 0");

    assertThat(changed("    return price\n        * count;", zero)).contains("    return 0;");
    assertThat(kotlin("    return price * count", zero)).contains("    return 0");
    assertThat(changed("    return price * count", zero)).as("no `;` in Java").isEmpty();
    assertThat(changed("    if (on) return price;", zero)).as("not the whole statement").isEmpty();
    assertThat(changed("    return on ? price : run(\"return \");", zero))
        .contains("    return 0;");
    assertThat(changed("    log(); return price;", zero)).isEmpty();
  }

  @Test
  void aRemovedAssignmentIsGoneOrLeavesItsCall() {
    var store = mutant(BuiltIn.REMOVE_ASSIGNMENT, "ISTORE", "remove assignment to total");
    var field = mutant(BuiltIn.REMOVE_FIELD_WRITE, "PUTFIELD", "remove write to total");

    assertThat(changed("    total = price * 2;", store)).contains("");
    assertThat(changed("    total = next(price);", store)).contains("    next(price);");
    assertThat(changed("    total = new Total(price);", store)).contains("    new Total(price);");
    assertThat(changed("    total = a.next() + 1;", store)).as("the call still runs").isEmpty();
    assertThat(changed("    total = next(price) ;", store)).contains("    next(price);");
    assertThat(changed("    this.total += price;", field)).contains("");
    assertThat(changed("    total <<= 1;", store)).contains("");
    assertThat(changed("    if (total == price) {", store)).isEmpty();
    assertThat(changed("    subtotal = price;", store)).isEmpty();
    assertThat(changed("    total = price", store)).as("no `;` in Java").isEmpty();
    assertThat(kotlin("    total = price * 2", store)).contains("");
    assertThat(kotlin("    total = next(price)", store)).contains("    next(price)");
  }

  @Test
  void aFlippedStepGoesTheOtherWay() {
    var down = mutant(BuiltIn.FLIP_INCREMENT, "IINC", "count by -1");
    var up = mutant(BuiltIn.FLIP_INCREMENT, "IINC", "left by 5");

    assertThat(changed("    count++;", down)).contains("    count--;");
    assertThat(changed("    ++count;", down)).contains("    --count;");
    assertThat(changed("    left -= 5;", up)).contains("    left += 5;");
    assertThat(changed("    left--;", up)).contains("    left++;");
    assertThat(changed("    count += 2;", down)).contains("    count -= 2;");
    assertThat(changed("    discount++;", down)).as("another name").isEmpty();
    assertThat(changed("    total = count++ + count++;", down)).as("twice").isEmpty();
    assertThat(changed("    ++counter;", down)).isEmpty();
  }

  @Test
  void aRemovedNegationTakesOutTheOneMinusThatNegates() {
    var negation = mutant(BuiltIn.REMOVE_NEGATION, "INEG", "remove negation");

    assertThat(changed("    return -price;", negation)).contains("    return price;");
    assertThat(changed("    var x = a * -b;", negation)).contains("    var x = a * b;");
    assertThat(changed("    var x = -1 * -y;", negation)).contains("    var x = -1 * y;");
    assertThat(changed("    var x = -(a + b);", negation)).contains("    var x = (a + b);");
    assertThat(changed("    var x = a - b;", negation)).as("a minus between two").isEmpty();
    assertThat(changed("    return -a + -b;", negation)).as("twice").isEmpty();
  }

  @Test
  void aKeptOperandIsTheOperationAsTheValueItKeeps() {
    var first = mutant(BuiltIn.KEEP_OPERAND, "IADD", "keep first", "price", "fee");
    var second = mutant(BuiltIn.KEEP_OPERAND, "IMUL", "keep second", "price", "fee");
    var call = mutant(BuiltIn.KEEP_OPERAND, "IADD", "keep first", "price", "fee()");

    assertThat(changed("    var total = price + fee;", first)).contains("    var total = price;");
    assertThat(changed("    var total = price * fee;", second)).contains("    var total = fee;");
    assertThat(changed("    var total = price + fee();", call)).as("fee() still runs").isEmpty();
    assertThat(
            changed(
                "    var total = price + fee;", mutant(BuiltIn.KEEP_OPERAND, "IADD", "keep first")))
        .isEmpty();
  }

  @Test
  void aChangedStringIsChangedWhereItIsOnce() {
    var string = mutant(BuiltIn.STRING_CONSTANT, "LDC", "string \"Hello\" to \"\"");

    assertThat(changed("    greet(\"Hello\", name);", string)).contains("    greet(\"\", name);");
    assertThat(changed("    greet(\"Hello\", \"Hello\");", string)).isEmpty();
    assertThat(changed("    greet(HELLO);", string)).isEmpty();
  }

  @Test
  void aRemovedThrowIsTheStatementGoneWhenItIsTheWholeStatement() {
    var removed = mutant(BuiltIn.REMOVE_THROW, "ATHROW", "remove throw");

    assertThat(changed("    throw new IllegalStateException(\"no; never\");", removed))
        .contains("");
    assertThat(changed("    if (x) throw new E();", removed)).isEmpty();
    assertThat(changed("    throw a; b();", removed)).isEmpty();
    assertThat(changed("    throw new E()", removed)).isEmpty();
  }

  @Test
  void aRemovedArmIsItsCaseGoneWhenItIsOneValueOnALineOfItsOwn() {
    var arm = mutant(BuiltIn.REMOVE_SWITCH_ARM, "TABLESWITCH", "arm 2 2");

    assertThat(changed("      case 2:", arm)).contains("");
    assertThat(changed("      case 2 -> \"two, of course\";", arm)).contains("");
    assertThat(changed("      case 2, 3 -> \"few\";", arm)).isEmpty();
    assertThat(changed("      case 2 -> {", arm)).isEmpty();
    assertThat(changed("      return 2;", arm)).isEmpty();
    assertThat(changed("      case 2 -> run()", arm)).isEmpty();
    assertThat(
            changed(
                "      case 2:",
                mutant(BuiltIn.REMOVE_SWITCH_ARM, "TABLESWITCH", "throwing arm 2 2")))
        .as("javac's default throws, which the source doesn't say")
        .isEmpty();
  }

  @Test
  void aDroppedCatchTypeIsTakenOutOfItsCatch() {
    var first = mutant(BuiltIn.DROP_CATCH_TYPE, "ATHROW", "no catch of java/io/IOException");
    var nested = mutant(BuiltIn.DROP_CATCH_TYPE, "ATHROW", "no catch of com/example/Shop$Closed");

    assertThat(changed("    } catch (IOException | RuntimeException e) {", first))
        .contains("    } catch (RuntimeException e) {");
    assertThat(changed("    } catch (RuntimeException | java.io.IOException e) {", first))
        .contains("    } catch (RuntimeException e) {");
    assertThat(changed("    } catch (Shop.Closed | IOException e) {", nested))
        .contains("    } catch (IOException e) {");
    assertThat(changed("    } catch (MyIOException | E e) {", first)).isEmpty();
    assertThat(changed("    } catch (IOException e) {", first)).isEmpty();
  }

  @Test
  void aChangedOperatorIsChangedBetweenItsOperands() {
    var minus = mutant(BuiltIn.MATH, "IADD", "ISUB", "price", "fee");

    assertThat(changed("    var total = price + fee + 1;", minus))
        .contains("    var total = price - fee + 1;");
    assertThat(changed("    return price\n        + fee > limit;", minus))
        .as("split over lines, the operator once")
        .contains("    return price\n        - fee > limit;");
    assertThat(changed("    total += fee;", minus)).contains("    total -= fee;");
    assertThat(changed("    var total = a + b + c;", minus)).isEmpty();
  }

  @Test
  void anOperatorWithoutItsOperandsChangesWhenTheStatementHasItOnce() {
    var divide = mutant(BuiltIn.MATH, "IMUL", "IDIV");
    var boundary = mutant(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE");

    assertThat(changed("    return a * b;", divide)).contains("    return a / b;");
    assertThat(changed("    return a * b * c;", divide)).isEmpty();
    assertThat(changed("    if (a >= b) {", boundary)).contains("    if (a > b) {");
    assertThat(changed("    if (a >= b && c >= d) {", boundary)).isEmpty();
    assertThat(changed("    a >>= 1;", boundary)).as("no compound comparison").isEmpty();
  }

  @Test
  void aReversedBooleanIsNegatedOrLosesItsNegation() {
    var reversed = mutant(BuiltIn.OTHER_COMPARISON, "IFEQ", "IFNE", "done");

    assertThat(changed("    if (done) {", reversed)).contains("    if (!done) {");
    assertThat(changed("    if (!done) {", reversed)).contains("    if (done) {");
    assertThat(changed("    if (done || done) {", reversed)).isEmpty();
    assertThat(
            changed(
                "    if (done) {",
                mutant(BuiltIn.LOGICAL_CONNECTOR, "IFLE", "&& and || swapped", "done")))
        .isEmpty();
    assertThat(changed("    if (a) {", mutant(BuiltIn.OTHER_COMPARISON, "IFEQ", "IFNE"))).isEmpty();
  }

  @Test
  void aConditionThatStartsWithAStringIsFoundInCode() {
    var always =
        mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "\"yes\".equals(answer)");

    assertThat(changed("    if (\"yes\".equals(answer)) {", always)).contains("    if (true) {");
  }

  @Test
  void aConditionIsFoundAsAWholeNameAndNotPartOfOne() {
    var underscore = mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "_ready");
    var dollar = mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "$ready");

    assertThat(changed("    if (_ready && not_ready) {", underscore))
        .contains("    if (true && not_ready) {");
    assertThat(changed("    if ($ready && is$ready) {", dollar))
        .contains("    if (true && is$ready) {");
    assertThat(
            changed(
                "    if (isReady) {",
                mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "ready")))
        .isEmpty();
  }

  @Test
  void aConditionOnANamedConstantIsFoundByItsOtherOperand() {
    var never = mutant(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "count", "50");

    assertThat(changed("    if (total >= MAX && count >= LIMIT) {", never))
        .contains("    if (total >= MAX && true) {");
  }

  @Test
  void aForcedConditionWithoutItsOperandsIsNotShown() {
    var unknown = mutant(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump");

    assertThat(changed("    if (ready) {", unknown)).isEmpty();
  }

  @Test
  void aCallAssignedIsLeftAsAStatementOnlyWhenItIsOneCall() {
    var store = mutant(BuiltIn.REMOVE_ASSIGNMENT, "ISTORE", "remove assignment to total");

    assertThat(changed("    total = next(price, 2);", store)).contains("    next(price, 2);");
    assertThat(changed("    total = a + next(price);", store)).isEmpty();
    assertThat(changed("    total = next(price).value;", store)).isEmpty();
  }

  @Test
  void aKeptOperandIsNotShownWhenTheOneDroppedIsACall() {
    var second = mutant(BuiltIn.KEEP_OPERAND, "IADD", "keep second", "price()", "fee");

    assertThat(changed("    var total = price() + fee;", second)).isEmpty();
  }

  @Test
  void aChangedReturnIsFoundAtTheStartAndWithSpaceAfter() {
    var zero = mutant(BuiltIn.RETURN_VALUE, "IRETURN", "return 0");

    assertThat(changed("return price;", zero)).contains("return 0;");
    assertThat(changed("    return price;  ", zero)).contains("    return 0;");
    assertThat(changed("    return run(() -> {\n      return 1;\n    });", zero)).isEmpty();
  }

  @Test
  void aRemovedArmIsOnlyACaseLine() {
    var arm = mutant(BuiltIn.REMOVE_SWITCH_ARM, "TABLESWITCH", "arm 2 2");

    assertThat(changed("      Runnable r = () -> run();", arm)).isEmpty();
    assertThat(changed("      case 2: run();", arm)).isEmpty();
  }

  @Test
  void aComparisonIsNotTakenForTheCompoundAssignmentOfAnotherOperator() {
    var edge = mutant(BuiltIn.BOUNDARY, "IF_ICMPGE", "IF_ICMPGT");

    assertThat(Advice.of(edge).before()).contains("<");
    assertThat(changed("    if (a <= b) {", edge)).isEmpty();
  }
}
