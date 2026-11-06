package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import io.mutastic.engine.fixture.Accessors;
import io.mutastic.engine.fixture.Annotated;
import io.mutastic.engine.fixture.Board;
import io.mutastic.engine.fixture.Kept;
import io.mutastic.engine.fixture.OwnAnnotations;
import io.mutastic.engine.fixture.Point;
import io.mutastic.engine.fixture.Pricing;
import io.mutastic.engine.fixture.Sites;
import io.mutastic.engine.fixture.Switches;
import io.mutastic.engine.fixture.Window;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MutationsTest {
  private final Mutations mutations = new Mutations(ClassFile.of());

  /// Every operator, negate's too: for tests of one operator, whatever its set.
  private final Mutations stronger =
      new Mutations(
          ClassFile.of(),
          new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));

  @Test
  void findsEveryOperatorInDiscount() throws Exception {
    var found = mutantsOf("discount");

    assertThat(found)
        .extracting(Mutant::operator, Mutant::original, Mutant::replacement, Mutant::line)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", 10),
            org.assertj.core.groups.Tuple.tuple(
                BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "always jump", 10),
            org.assertj.core.groups.Tuple.tuple(
                BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", 10),
            org.assertj.core.groups.Tuple.tuple(BuiltIn.MATH, "IDIV", "IMUL", 11),
            org.assertj.core.groups.Tuple.tuple(BuiltIn.RETURN_VALUE, "IRETURN", "return 0", 11),
            org.assertj.core.groups.Tuple.tuple(BuiltIn.RETURN_VALUE, "IRETURN", "return 1", 13));
    // `return 0` can't be changed to return 0: it's changed to return 1.
    assertThat(found).allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  @Test
  void readsWhatTheChangeWorksOnFromTheBytecode() throws Exception {
    assertThat(only("discount", BuiltIn.BOUNDARY).operands()).containsExactly("total", "100");
    assertThat(only("discount", BuiltIn.MATH).operands()).containsExactly("total", "10");
    // longs compare with LCMP first: the operands are what went into it.
    assertThat(only("overdue", BuiltIn.BOUNDARY).operands()).containsExactly("days", "30");
    assertThat(only("total", BuiltIn.MATH).operands()).containsExactly("price", "quantity");
  }

  @Test
  void readsFieldsAndUnboxedValuesAsTheSourceNamesThem() throws Exception {
    assertThat(operandsIn(Window.class, "after", BuiltIn.BOUNDARY)).containsExactly("start", "day");
    assertThat(operandsIn(Window.class, "before", BuiltIn.BOUNDARY)).containsExactly("day", "end");
    assertThat(operandsIn(Window.class, "state", BuiltIn.OTHER_COMPARISON)).containsExactly("open");
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
        .containsEntry("getName", "")
        .containsEntry("withLast", "")
        .containsEntry("withBoxed", "")
        .containsEntry("withActive", "")
        .containsEntry("withFirst", "")
        .containsEntry("withTags", "")
        .containsEntry("withRegion", "");
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
        .containsEntry("capped", "user: @DoNotMutate on `capped()`: tested elsewhere")
        .containsEntry("generated", "generated: @Generated on `generated()`")
        .containsEntry("toString", "");
  }

  /// `mutastic.yaml`'s rules: `skip:` is the project's own decision, `mutate:` keeps what a filter
  /// would call arid (and so what only served it), and the code's own `@DoMutate` beats a rule.
  @Test
  void rulesSkipOrKeepAndTheCodesOwnSayComesFirst() throws Exception {
    var policy =
        new Policy(
            List.of(
                new Rule(false, "io.mutastic.engine.fixture.Accessors#getLast", "named oddly"),
                new Rule(true, "io.mutastic.engine.fixture.Board#toString", "shown to people"),
                new Rule(false, "io.mutastic.engine.fixture.Annotated", "not this class")),
            List.of());
    var ruled = new Mutations(ClassFile.of(), policy);

    assertThat(skipsIn(ruled, Accessors.class))
        .containsEntry(
            "getLast",
            "user: named oddly (rule `skip: io.mutastic.engine.fixture.Accessors#getLast`)");
    assertThat(skipsIn(ruled, Board.class)).containsEntry("toString", "").containsEntry("cell", "");
    assertThat(skipsIn(ruled, Annotated.class))
        .containsEntry("toString", "")
        .containsEntry("generated", "generated: @Generated on `generated()`");
  }

  /// No dependency on mutastic needed: a team's own annotation of the name works the same.
  @Test
  void aTeamsOwnAnnotationWorksTheSame() throws Exception {
    assertThat(skipsIn(OwnAnnotations.class))
        .containsEntry("capped", "user: @DoNotMutate on `capped()`: tested elsewhere");
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
    var pricing = mutated(only("discount", BuiltIn.BOUNDARY));

    assertThat(call(pricing, "discount", 100)).isEqualTo(0); // was 10
    assertThat(call(pricing, "discount", 101)).isEqualTo(10);
  }

  @Test
  void aComparisonBecomesOneThatDiffersOnOneSide() throws Exception {
    // `total >= 100` becomes `total == 100`: the same at 100 and below, not above.
    var pricing =
        mutated(
            stronger.find(model(Pricing.class)).stream()
                .filter(mutant -> mutant.methodName().equals("discount"))
                .filter(mutant -> mutant.replacement().equals("IF_ICMPNE"))
                .findFirst()
                .orElseThrow());

    assertThat(call(pricing, "discount", 100)).isEqualTo(10);
    assertThat(call(pricing, "discount", 99)).isEqualTo(0);
    assertThat(call(pricing, "discount", 250)).isEqualTo(0); // was 25
  }

  @Test
  void negationSwapsTheBranches() throws Exception {
    var pricing = mutated(only("discount", BuiltIn.OTHER_COMPARISON));

    assertThat(call(pricing, "discount", 50)).isEqualTo(5); // was 0
    assertThat(call(pricing, "discount", 250)).isEqualTo(0); // was 25
  }

  @Test
  void mathSwapsTheOperator() throws Exception {
    var pricing = mutated(only("discount", BuiltIn.MATH));

    assertThat(call(pricing, "discount", 250)).isEqualTo(2500); // was 25
  }

  @Test
  void aComputedNumberReturnsZero() throws Exception {
    var mutant =
        mutantsOf("discount").stream()
            .filter(found -> found.operator() == BuiltIn.RETURN_VALUE && found.skipped().isEmpty())
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
    var audit = mutated(only("audit", BuiltIn.RETURN_VALUE));

    assertThat(total.getClass().getMethod("total", long.class, int.class).invoke(total, 10L, 3))
        .isEqualTo(25L); // was 35
    assertThat(audit.getClass().getMethod("audit").invoke(audit)).isEqualTo(List.of());
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
    assertThat(only("discount", BuiltIn.BOUNDARY).ordinal()).isZero();
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
  void aSwitchOnAStringSkipsJavacsEqualsButNotTheUsers() throws Exception {
    var model = model(Switches.class);
    var found = mutations.find(model);
    var inSize = found.stream().filter(mutant -> mutant.methodName().equals("size")).toList();
    var inIsSmall = found.stream().filter(mutant -> mutant.methodName().equals("isSmall")).toList();

    assertThat(inSize)
        .filteredOn(mutant -> mutant.skipped().isPresent())
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped()).contains("generated: part of a switch on a String"));
    // The cases' own returns are the user's.
    assertThat(inSize)
        .filteredOn(mutant -> mutant.operator() == BuiltIn.RETURN_VALUE)
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
    assertThat(inIsSmall).isNotEmpty().allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// A boundary where both sides give the same value at the edge is equivalent; one where they
  /// don't, or the body does more, is mutated. Its other mutants always run.
  @Test
  void aBoundaryThatGivesTheSameValueAtTheEdgeIsEquivalent() throws Exception {
    var found = mutations.find(model(Sites.class));
    var equivalent = "equivalent: both sides give the same value at the edge";

    // The loop's own boundary, `i < values.length`, runs; the `if`'s is equivalent.
    assertThat(boundariesIn(found, "largest")).containsExactly("", equivalent);
    assertThat(boundariesIn(found, "smaller")).containsExactly(equivalent);
    assertThat(boundariesIn(found, "countAbove")).containsExactly("", "");
    assertThat(boundariesIn(found, "firstOrNone")).containsExactly("");
    assertThat(found)
        .filteredOn(
            mutant ->
                mutant.methodName().equals("largest")
                    && mutant.operator() == BuiltIn.REMOVE_CONDITIONAL)
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// Arithmetic that only sizes a collection or sets a wait is arid, as is removing a sleep; the
  /// same arithmetic returned is mutated.
  @Test
  void capacityAndWaitsAreArid() throws Exception {
    var skips = skipsIn(Sites.class);

    assertThat(skips)
        .containsEntry("sized", "arid: only sizes a collection's first allocation")
        .containsEntry("doubled", "");
    assertThat(mutations.find(model(Sites.class)))
        .filteredOn(mutant -> mutant.methodName().equals("pause"))
        .extracting(mutant -> mutant.skipped().orElse(""))
        .containsExactlyInAnyOrder("arid: only sets how long to wait", "arid: only waits");
  }

  /// Each boundary's skip reason in `method`, in order, "" for one that runs.
  private static List<String> boundariesIn(List<Mutant> found, String method) {
    return found.stream()
        .filter(
            mutant -> mutant.methodName().equals(method) && mutant.operator() == BuiltIn.BOUNDARY)
        .map(mutant -> mutant.skipped().orElse(""))
        .toList();
  }

  @Test
  void aCloseTheUserWritesIsMutated() throws Exception {
    assertThat(mutations.find(model(Switches.class)))
        .filteredOn(mutant -> mutant.methodName().equals("done"))
        .singleElement()
        .satisfies(
            mutant -> {
              assertThat(mutant.operator()).isEqualTo(BuiltIn.REMOVE_VOID_CALL);
              assertThat(mutant.skipped()).isEmpty();
            });
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
    assertThat(userCode).extracting(Mutant::operator).containsExactly(BuiltIn.RETURN_VALUE);
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
            BuiltIn.BOUNDARY,
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
            BuiltIn.BOUNDARY,
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
            BuiltIn.BOUNDARY,
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
    return skipsIn(mutations, type);
  }

  private static Map<String, String> skipsIn(Mutations mutations, Class<?> type) throws Exception {
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
    return stronger.find(model(type)).stream()
        .filter(mutant -> mutant.methodName().equals(method) && mutant.operator().equals(operator))
        .findFirst()
        .orElseThrow()
        .operands();
  }

  private Mutant only(String method, Operator operator) throws Exception {
    return stronger.find(model(Pricing.class)).stream()
        .filter(mutant -> mutant.methodName().equals(method) && mutant.operator().equals(operator))
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
