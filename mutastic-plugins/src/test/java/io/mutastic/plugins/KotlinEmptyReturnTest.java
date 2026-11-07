package io.mutastic.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Which returns count, by the shape of Kotlin's types and its marker on the class. What it does on
/// kotlinc's own output is in the CLI's end-to-end test, on `samples/kotlin`: Java stand-ins here
/// keep this test free of the Kotlin compiler.
class KotlinEmptyReturnTest {
  private static final String ANNOTATION =
      """
      package kotlin;
      import java.lang.annotation.*;
      @Retention(RetentionPolicy.RUNTIME) public @interface Metadata {}
      """;
  private static final String RANGE =
      """
      package kotlin.ranges;
      public class IntRange {
        public static final Companion Companion = new Companion();
        public static class Companion {
          public IntRange getEMPTY() { return new IntRange(); }
        }
      }
      """;
  private static final String KOTLIN_CLASS =
      """
      package fixture;
      import kotlin.ranges.IntRange;
      @kotlin.Metadata
      public class Shelf {
        public IntRange slots() { return new IntRange(); }
        public IntRange none() { return IntRange.Companion.getEMPTY(); }
        public IntRange either(boolean which) {
          return which ? IntRange.Companion.getEMPTY() : new IntRange();
        }
        public String name() { return "shelf"; }
      }
      """;
  private static final String JAVA_CLASS =
      """
      package fixture;
      import kotlin.ranges.IntRange;
      public class Plain {
        public IntRange slots() { return new IntRange(); }
      }
      """;

  private static Compiled compiled;
  private final KotlinEmptyReturn operator = new KotlinEmptyReturn();

  @BeforeAll
  static void compile() throws Exception {
    compiled =
        Compiled.of(
            Map.of(
                "kotlin/Metadata.java", ANNOTATION,
                "kotlin/ranges/IntRange.java", RANGE,
                "fixture/Shelf.java", KOTLIN_CLASS,
                "fixture/Plain.java", JAVA_CLASS));
  }

  private List<Mutator.Change> changesIn(String className, String method) {
    return changes(compiled.model(className), method);
  }

  private List<Mutator.Change> changes(ClassModel owner, String name) {
    var found = new ArrayList<Mutator.Change>();
    for (MethodModel method : owner.methods()) {
      if (method.methodName().equalsString(name)) {
        var code = method.code().orElseThrow().elementList();
        for (int at = 0; at < code.size(); at++) {
          CodeElement element = code.get(at);
          if (element instanceof Instruction instruction) {
            found.addAll(operator.changes(new Mutator.Site(owner, method, code, at, instruction)));
          }
        }
      }
    }
    return found;
  }

  @Test
  void aKotlinRangeReturnedIsAChange() {
    assertThat(changesIn("fixture.Shelf", "slots"))
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.label()).isEqualTo("return empty Lkotlin/ranges/IntRange;");
              assertThat(change.skipped()).isEmpty();
            });
  }

  @Test
  void returningTheEmptyValueAlreadyIsEquivalent() {
    assertThat(changesIn("fixture.Shelf", "none"))
        .singleElement()
        .satisfies(change -> assertThat(change.skipped().orElseThrow()).startsWith("equivalent:"));
  }

  /// A jump lands on the return: what's before it isn't the only way there.
  @Test
  void anEmptyValueOnOnlyOnePathIsntEquivalent() {
    assertThat(changesIn("fixture.Shelf", "either"))
        .singleElement()
        .satisfies(change -> assertThat(change.skipped()).isEmpty());
  }

  @Test
  void anotherTypeIsntOne() {
    assertThat(changesIn("fixture.Shelf", "name")).isEmpty();
  }

  @Test
  void javaIsntKotlin() {
    assertThat(changesIn("fixture.Plain", "slots")).isEmpty();
  }

  @Test
  void aCardSaysWhichValue() {
    var words =
        operator.words(
            new Mutant(
                "fixture/Shelf",
                "slots",
                "()Lkotlin/ranges/IntRange;",
                0,
                1,
                operator,
                "ARETURN",
                "return empty Lkotlin/ranges/IntRange;",
                Optional.empty()));

    assertThat(words.headline())
        .isEqualTo("no test looks at what's in the range `slots()` returns");
    assertThat(words.help()).isEqualTo("assert the values in the range `slots()` returns");
    assertThat(words.changed()).isEqualTo("returns an empty range");
  }

  @Test
  void itSaysItsNameAndWhatItDoes() {
    assertThat(operator.id()).isEqualTo("kotlin/empty-return");
    assertThat(operator.explanation()).contains("range");
    assertThat(operator.onCalls()).isFalse();
    assertThat(operator.tier()).isEqualTo(Mutator.Tier.EXTENDED);
  }
}
