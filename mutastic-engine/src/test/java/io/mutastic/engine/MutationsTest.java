package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.engine.fixture.Accessors;
import io.mutastic.engine.fixture.Annotated;
import io.mutastic.engine.fixture.Board;
import io.mutastic.engine.fixture.Kept;
import io.mutastic.engine.fixture.Point;
import io.mutastic.engine.fixture.Pricing;
import io.mutastic.engine.fixture.Window;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MutationsTest {
  private final Mutations mutations = new Mutations(ClassFile.of());

  @Test
  void findsEveryOperatorInDiscount() throws Exception {
    var found = mutantsOf("discount");

    assertThat(found)
        .extracting(Mutant::operator, Mutant::original, Mutant::replacement, Mutant::line)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", 10),
            org.assertj.core.groups.Tuple.tuple(
                Operator.NEGATE_CONDITIONAL, "IF_ICMPLT", "IF_ICMPGE", 10),
            org.assertj.core.groups.Tuple.tuple(Operator.MATH, "IDIV", "IMUL", 11),
            org.assertj.core.groups.Tuple.tuple(
                Operator.RETURN_DEFAULT, "IRETURN", "return default", 11),
            org.assertj.core.groups.Tuple.tuple(
                Operator.RETURN_DEFAULT, "IRETURN", "return default", 13));
    assertThat(found.getLast().skipped()).contains("equivalent: already returns the default");
  }

  @Test
  void readsWhatTheChangeWorksOnFromTheBytecode() throws Exception {
    assertThat(only("discount", Operator.BOUNDARY).operands()).containsExactly("total", "100");
    assertThat(only("discount", Operator.MATH).operands()).containsExactly("total", "10");
    // longs compare with LCMP first: the operands are what went into it.
    assertThat(only("overdue", Operator.BOUNDARY).operands()).containsExactly("days", "30");
    assertThat(only("total", Operator.MATH).operands()).containsExactly("price", "quantity");
  }

  @Test
  void readsFieldsAndUnboxedValuesAsTheSourceNamesThem() throws Exception {
    assertThat(operandsIn(Window.class, "after", Operator.BOUNDARY))
        .containsExactly("start", "day");
    assertThat(operandsIn(Window.class, "before", Operator.BOUNDARY)).containsExactly("day", "end");
    assertThat(operandsIn(Window.class, "state", Operator.NEGATE_CONDITIONAL))
        .containsExactly("open");
  }

  /// Trivial accessors, by exact bytecode shape and matching name, are arid; anything with logic,
  /// or named for another field, is mutated.
  @Test
  void trivialAccessorsAreAridAndNearMissesAreMutated() throws Exception {
    var skips = skipsIn(Accessors.class);

    assertThat(skips)
        .containsEntry("getFirst", "arid: trivial getter of first")
        .containsEntry("isActive", "arid: trivial getter of active")
        .containsEntry("getCount", "arid: trivial getter of count")
        .containsEntry("getRegion", "arid: trivial getter of region")
        .containsEntry("first", "arid: trivial getter of first")
        .containsEntry("withCount", "arid: trivial setter of count")
        .containsEntry("count", "arid: trivial setter of count");
    assertThat(skips)
        .containsEntry("getLast", "")
        .containsEntry("getBoxed", "")
        .containsEntry("getTags", "")
        .containsEntry("getCached", "")
        .containsEntry("getName", "");
  }

  @Test
  void debugRepresentationsAndWhatOnlyServesThemAreArid() throws Exception {
    var skips = skipsIn(Board.class);

    assertThat(skips)
        .containsEntry("toString", "arid: `toString()` is a debug representation")
        .containsEntry("cell", "arid: `cell()` only serves `toString()`")
        .containsEntry("lambda$toString$0", "arid: a lambda in `toString()`")
        .containsEntry(
            "hashCode", "arid: `hashCode()` may return any value consistent with `equals()`")
        .containsEntry("rows", "")
        .containsEntry("equals", "");
  }

  @Test
  void annotationsSkipWithTheTeamsReasonOrKeep() throws Exception {
    var skips = skipsIn(Annotated.class);

    assertThat(skips)
        .containsEntry(
            "retries", "user: @DoNotMutate on `retries()`: measured in the load test, not here")
        .containsEntry("generated", "generated: @Generated on `generated()`")
        .containsEntry("toString", "");
  }

  @Test
  void aRecordAccessorWithLogicIsMutated() throws Exception {
    var skips = skipsIn(Point.class);

    assertThat(skips).containsEntry("x", "arid: trivial getter of x").containsEntry("y", "");
  }

  @Test
  void doMutateBringsTrivialAccessorsBack() throws Exception {
    assertThat(skipsIn(Kept.class)).containsEntry("getName", "");
  }

  @Test
  void everyMutantVerifies() throws Exception {
    var model = model(Pricing.class);
    var found = mutations.find(model);

    assertThat(found).hasSizeGreaterThan(10);
    assertThat(found).anyMatch(mutant -> !mutant.copies().isEmpty());
    for (Mutant mutant : found) {
      assertThat(mutations.verify(mutations.apply(model, mutant))).as(mutant.toString()).isEmpty();
    }
  }

  @Test
  void boundaryMovesTheEdge() throws Exception {
    var pricing = mutated(only("discount", Operator.BOUNDARY));

    assertThat(call(pricing, "discount", 100)).isEqualTo(0); // was 10
    assertThat(call(pricing, "discount", 101)).isEqualTo(10);
  }

  @Test
  void negationSwapsTheBranches() throws Exception {
    var pricing = mutated(only("discount", Operator.NEGATE_CONDITIONAL));

    assertThat(call(pricing, "discount", 50)).isEqualTo(5); // was 0
    assertThat(call(pricing, "discount", 250)).isEqualTo(0); // was 25
  }

  @Test
  void mathSwapsTheOperator() throws Exception {
    var pricing = mutated(only("discount", Operator.MATH));

    assertThat(call(pricing, "discount", 250)).isEqualTo(2500); // was 25
  }

  @Test
  void returnDefaultReturnsZero() throws Exception {
    var mutant =
        mutantsOf("discount").stream()
            .filter(
                found -> found.operator() == Operator.RETURN_DEFAULT && found.skipped().isEmpty())
            .findFirst()
            .orElseThrow();
    var pricing = mutated(mutant);

    assertThat(call(pricing, "discount", 250)).isEqualTo(0);
  }

  @Test
  void longArithmeticAndReferenceReturnsToo() throws Exception {
    var total =
        mutated(
            mutantsOf("total").stream()
                .filter(found -> found.original().equals("LADD"))
                .findFirst()
                .orElseThrow());
    var audit = mutated(only("audit", Operator.RETURN_DEFAULT));

    assertThat(total.getClass().getMethod("total", long.class, int.class).invoke(total, 10L, 3))
        .isEqualTo(25L); // was 35
    assertThat(audit.getClass().getMethod("audit").invoke(audit)).isNull();
  }

  @Test
  void removingAVoidCallDropsItsEffect() throws Exception {
    var calls = mutantsOf("checkout");
    var record =
        calls.stream()
            .filter(found -> found.original().contains(".record("))
            .findFirst()
            .orElseThrow();
    var pricing = mutated(record);

    pricing.getClass().getMethod("checkout", String.class).invoke(pricing, "order-1");

    assertThat(pricing.getClass().getMethod("audit").invoke(pricing)).isEqualTo(List.of());
    assertThat(record.original())
        .isEqualTo("INVOKEVIRTUAL io/mutastic/engine/fixture/Pricing.record(Ljava/lang/String;)V");
  }

  @Test
  void outputOnlyCallsAreSkipped() throws Exception {
    var println =
        mutantsOf("checkout").stream()
            .filter(found -> found.original().contains("PrintStream"))
            .findFirst()
            .orElseThrow();

    assertThat(println.skipped()).contains("arid: only writes output");
  }

  @Test
  void callsAreCountedApartFromInstructions() throws Exception {
    // The void-call operator doesn't move instruction sites: discount()'s branch is still site 0.
    assertThat(only("discount", Operator.BOUNDARY).ordinal()).isZero();
    assertThat(mutantsOf("checkout")).extracting(Mutant::ordinal).containsExactly(0, 1);
  }

  @Test
  void aFinallyBlocksCopiesAreOneMutant() throws Exception {
    var unlocks =
        mutantsOf("withLock").stream()
            .filter(found -> found.original().contains(".unlock("))
            .toList();

    assertThat(unlocks).singleElement().satisfies(unlock -> assertThat(unlock.copies()).hasSize(1));
  }

  @Test
  void theMergedMutantChangesEveryWayOut() throws Exception {
    var unlock =
        mutantsOf("withLock").stream()
            .filter(found -> found.original().contains(".unlock("))
            .findFirst()
            .orElseThrow();
    var pricing = mutated(unlock);
    var withLock =
        pricing
            .getClass()
            .getMethod(
                "withLock",
                java.util.concurrent.locks.ReentrantLock.class,
                java.util.function.IntSupplier.class);

    var normal = new java.util.concurrent.locks.ReentrantLock();
    withLock.invoke(pricing, normal, (java.util.function.IntSupplier) () -> 1);
    var exceptional = new java.util.concurrent.locks.ReentrantLock();
    try {
      withLock.invoke(
          pricing,
          exceptional,
          (java.util.function.IntSupplier)
              () -> {
                throw new IllegalStateException();
              });
    } catch (java.lang.reflect.InvocationTargetException expected) {
      // the action threw, as intended
    }

    assertThat(normal.isHeldByCurrentThread()).isTrue();
    assertThat(exceptional.isHeldByCurrentThread()).isTrue();
  }

  @Test
  void theSameCallTwiceWithoutAFinallyIsTwoMutants() throws Exception {
    assertThat(mutantsOf("clearTwice"))
        .hasSize(2)
        .allSatisfy(mutant -> assertThat(mutant.copies()).isEmpty());
  }

  @Test
  void tryWithResourcesClosingIsSkippedButTheBodyIsNot() throws Exception {
    var found = mutantsOf("firstLine");
    var generated = found.stream().filter(mutant -> mutant.skipped().isPresent()).toList();
    var userCode = found.stream().filter(mutant -> mutant.skipped().isEmpty()).toList();

    assertThat(generated)
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped())
                    .contains("generated: closes a try-with-resources resource"));
    assertThat(generated)
        .extracting(Mutant::original)
        .anyMatch(original -> original.contains(".close()V"))
        .anyMatch(original -> original.contains("addSuppressed"))
        .anyMatch(original -> original.equals("IFNULL"));
    // `return reader.readLine()` is the user's: its mutants still run.
    assertThat(userCode).extracting(Mutant::operator).containsExactly(Operator.RETURN_DEFAULT);
  }

  @Test
  void aCloseTheUserWroteIsStillMutated() throws Exception {
    assertThat(mutantsOf("closeTwice"))
        .hasSize(2)
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  @Test
  void leavesCompilerWrittenCodeAlone() throws Exception {
    assertThat(mutations.find(model(Pricing.Tier.class))).isEmpty();
    assertThat(mutations.find(model(Pricing.Line.class)))
        .extracting(Mutant::methodName)
        .containsOnly("item", "price");
  }

  @Test
  void theIdIgnoresTheLine() {
    var here =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            5,
            Operator.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    var moved =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            9,
            Operator.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    var otherSite =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            1,
            5,
            Operator.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());

    assertThat(moved.id()).isEqualTo(here.id()).matches("m[0-9a-f]{8}");
    assertThat(otherSite.id()).isNotEqualTo(here.id());
  }

  private List<Mutant> mutantsOf(String method) throws Exception {
    return mutations.find(model(Pricing.class)).stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .toList();
  }

  /// Each method's skip reason ("" when its mutants run), for methods with a mutant.
  private Map<String, String> skipsIn(Class<?> type) throws Exception {
    var skips = new java.util.TreeMap<String, String>();
    var model = model(type);
    mutations.prepare(List.of(model));
    for (Mutant mutant : mutations.find(model)) {
      skips.merge(mutant.methodName(), mutant.skipped().orElse(""), (kept, ignored) -> kept);
    }
    return skips;
  }

  private List<String> operandsIn(Class<?> type, String method, Operator operator)
      throws Exception {
    return mutations.find(model(type)).stream()
        .filter(mutant -> mutant.methodName().equals(method) && mutant.operator() == operator)
        .findFirst()
        .orElseThrow()
        .operands();
  }

  private Mutant only(String method, Operator operator) throws Exception {
    return mutantsOf(method).stream()
        .filter(mutant -> mutant.operator() == operator)
        .findFirst()
        .orElseThrow();
  }

  /// A new instance of `Pricing` with the mutant in.
  private Object mutated(Mutant mutant) throws Exception {
    var bytes = mutations.apply(model(Pricing.class), mutant);
    var type =
        new SingleClassLoader(Pricing.class.getName(), bytes).loadClass(Pricing.class.getName());
    return type.getConstructor().newInstance();
  }

  private static Object call(Object pricing, String method, int argument) throws Exception {
    return pricing.getClass().getMethod(method, int.class).invoke(pricing, argument);
  }

  static ClassModel model(Class<?> type) throws Exception {
    try (var in =
        type.getResourceAsStream(
            type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class")) {
      return ClassFile.of().parse(in.readAllBytes());
    }
  }

  /// Defines one class from the given bytes, and leaves everything else to its parent.
  static final class SingleClassLoader extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    SingleClassLoader(String name, byte[] bytes) {
      super(MutationsTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes;
    }

    @Override
    protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
      if (!requested.equals(name)) {
        return super.loadClass(requested, resolve);
      }
      synchronized (getClassLoadingLock(requested)) {
        var loaded = findLoadedClass(requested);
        return loaded != null ? loaded : defineClass(requested, bytes, 0, bytes.length);
      }
    }
  }
}
