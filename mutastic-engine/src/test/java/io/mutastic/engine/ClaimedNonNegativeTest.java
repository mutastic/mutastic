package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// Which annotations count as the code saying a value is never negative, and where they're read
/// from: the class file's attributes. That each skip is equivalent wherever the claim holds,
/// CardTruthTest checks by running it.
class ClaimedNonNegativeTest {
  private static final String RUNS = "runs";

  /// The annotations, with the real ones' names, retention and targets.
  private static final List<Javac.Source> ANNOTATIONS =
      List.of(
          annotation(
              "org.checkerframework.checker.index.qual.NonNegative",
              "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)",
              ""),
          annotation(
              "org.checkerframework.checker.index.qual.Positive",
              "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)",
              ""),
          annotation(
              "org.checkerframework.checker.index.qual.IndexFor",
              "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)",
              "String[] value();"),
          annotation(
              "androidx.annotation.IntRange",
              "@Retention(RetentionPolicy.CLASS) @Target({ElementType.PARAMETER,"
                  + " ElementType.METHOD, ElementType.FIELD})",
              "long from() default Long.MIN_VALUE; long to() default Long.MAX_VALUE;"),
          annotation("javax.annotation.Nonnegative", "@Retention(RetentionPolicy.RUNTIME)", ""),
          annotation(
              "jakarta.validation.constraints.Min",
              "@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER,"
                  + " ElementType.TYPE_USE})",
              "long value();"));

  private static final String STOCK =
      """
      package c;
      import org.checkerframework.checker.index.qual.NonNegative;
      class Stock {
        @NonNegative int held;
        static @NonNegative int count(int[] items) { return items.length; }
        @NonNegative int left() { return held; }
      }
      """;

  private static final String COUNTS =
      """
      package c;
      import androidx.annotation.IntRange;
      import org.checkerframework.checker.index.qual.IndexFor;
      import org.checkerframework.checker.index.qual.NonNegative;
      import org.checkerframework.checker.index.qual.Positive;
      class Counts {
        static int othersField(Stock stock) { return stock.held > 0 ? 1 : 2; }
        static int othersReturn(int[] items) { return Stock.count(items) > 0 ? 1 : 2; }
        static int inherited(Shelf shelf) { return shelf.left() > 0 ? 1 : 2; }
        static int positive(@Positive int n) { return n > 0 ? 1 : 2; }
        static int index(@IndexFor("items") int i, int[] items) { return i > 0 ? 1 : 2; }
        static int unbounded(@IntRange(to = 5) int n) { return n > 0 ? 1 : 2; }
        static int belowZero(@IntRange(from = -1) int n) { return n > 0 ? 1 : 2; }
        static int unchecked(@javax.annotation.Nonnegative int n) { return n > 0 ? 1 : 2; }
        static int validated(@jakarta.validation.constraints.Min(0) int n) { return n > 0 ? 1 : 2; }
        static int incremented(@NonNegative int n) { n++; return n > 0 ? 1 : 2; }
        static int element(@NonNegative int[] items) { return items[0] > 0 ? 1 : 2; }
        static int wide(long before, @NonNegative int n) { return n > 0 ? 1 : 2; }
      }
      class Shelf extends Stock {}
      """;

  @Test
  void readsAnotherClassOfTheModule() throws Exception {
    assertThat(skipped())
        .containsEntry(
            "othersField",
            "equivalent: differs only when stock.held is negative. That can't happen because"
                + " stock.held is @NonNegative.")
        .containsEntry(
            "othersReturn",
            "equivalent: differs only when Stock.count(items) is negative. That can't happen"
                + " because Stock.count(items) is @NonNegative.")
        .containsKey("inherited");
  }

  @Test
  void countsOnlyWhatAToolChecks() throws Exception {
    assertThat(skipped())
        .containsKeys("positive", "index", "wide")
        .hasEntrySatisfying("index", reason -> assertThat(reason).endsWith(" is @IndexFor."))
        .doesNotContainKeys("unbounded", "belowZero", "unchecked", "validated");
    assertThat(belowZeroChanges())
        .containsEntry("unbounded", RUNS)
        .containsEntry("belowZero", RUNS)
        .containsEntry("unchecked", RUNS)
        .containsEntry("validated", RUNS);
  }

  @Test
  void aValueChangedOrBesideTheClaimIsntClaimed() throws Exception {
    assertThat(belowZeroChanges())
        .containsEntry("incremented", RUNS)
        .containsEntry("element", RUNS);
  }

  @Test
  void withoutNamesSaysTheValue() throws Exception {
    assertThat(skipped("-g:none"))
        .hasEntrySatisfying(
            "positive",
            reason ->
                assertThat(reason)
                    .isEqualTo(
                        "equivalent: differs only when the value compared is negative. That can't"
                            + " happen because it's @Positive."));
  }

  /// Each of `Counts`'s methods whose comparison's change that differs only below zero is skipped
  /// by this rule, with its reason.
  private static Map<String, String> skipped(String... options) throws Exception {
    return belowZeroChanges(options).entrySet().stream()
        .filter(entry -> !entry.getValue().equals(RUNS))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /// What happens to each of `Counts`'s methods' `n > 0` becoming `n != 0`, which differs only
  /// below zero: its skip's reason, or [#RUNS]. `Stock` and `Shelf` are in the module.
  private static Map<String, String> belowZeroChanges(String... options) throws Exception {
    var classes =
        Javac.compile(
            List.of(new Javac.Source("c.Counts", COUNTS), new Javac.Source("c.Stock", STOCK)),
            ANNOTATIONS,
            options);
    var models = new ArrayList<ClassModel>();
    for (var name : List.of("c.Counts", "c.Stock", "c.Shelf")) {
      models.add(ClassFile.of().parse(classes.get(name)));
    }
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    mutations.prepare(models);
    return mutations.find(models.getFirst()).stream()
        .filter(mutant -> mutant.operator() == BuiltIn.COMPARISON)
        .filter(mutant -> mutant.replacement().equals("IFEQ"))
        .collect(
            Collectors.toMap(
                Mutant::methodName, mutant -> mutant.skipped().orElse(RUNS), (one, other) -> one));
  }

  private static Javac.Source annotation(String name, String meta, String body) {
    var at = name.lastIndexOf('.');
    return new Javac.Source(
        name,
        """
        package %s;
        import java.lang.annotation.*;
        %s
        public @interface %s { %s }
        """
            .formatted(name.substring(0, at), meta, name.substring(at + 1), body));
  }
}
