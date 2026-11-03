package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.engine.fixture.Pricing;
import io.mutastic.engine.fixture.Rates;
import io.mutastic.engine.fixture.RecordingProbes;
import java.lang.classfile.ClassFile;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoverageTest {
  private final Coverage coverage =
      new Coverage(
          ClassFile.of(), java.lang.constant.ClassDesc.of(RecordingProbes.class.getName()));
  private Object pricing;

  @BeforeEach
  void instrumentedPricing() throws Exception {
    RecordingProbes.LINES.clear();
    RecordingProbes.VALUES.clear();
    RecordingProbes.CALLS.clear();
    var bytes = coverage.instrument(MutationsTest.model(Pricing.class));
    assertThat(ClassFile.of().verify(bytes)).isEmpty();
    var type =
        new MutationsTest.SingleClassLoader(Pricing.class.getName(), bytes)
            .loadClass(Pricing.class.getName());
    pricing = type.getConstructor().newInstance();
  }

  @Test
  void recordsWholeNumberArgumentsLongsIncluded() throws Exception {
    pricing.getClass().getMethod("total", long.class, int.class).invoke(pricing, 7_000_000_000L, 3);

    assertThat(RecordingProbes.VALUES)
        .extracting(value -> value[1])
        .containsExactly(7_000_000_000L, 3L);
  }

  @Test
  void namesInputsForTheMutantsMethodFromTheTestsThatRanIt() throws Exception {
    pricing.getClass().getMethod("total", long.class, int.class).invoke(pricing, 10L, 3);
    var ids = RecordingProbes.VALUES.stream().map(value -> (int) value[0]).toList();
    coverage.recordValues("PricingTest#small", ids.get(0), Set.of(10L));
    coverage.recordValues("PricingTest#small", ids.get(1), Set.of(3L));
    coverage.recordValues("PricingTest#big", ids.get(0), Set.of(900L));
    var mutant =
        new Mutant(
            "io/mutastic/engine/fixture/Pricing",
            "total",
            "(JI)J",
            0,
            18,
            BuiltIn.MATH,
            "LMUL",
            "LDIV",
            Optional.empty());

    assertThat(coverage.inputs(mutant, List.of("PricingTest#small", "PricingTest#big")))
        .containsExactly(
            Map.entry("price", List.of(10L, 900L)), Map.entry("quantity", List.of(3L)));
    assertThat(coverage.inputs(mutant, List.of("PricingTest#big")))
        .containsExactly(Map.entry("price", List.of(900L)));
  }

  @Test
  void booleansAndObjectsAreNotRecorded() throws Exception {
    pricing.getClass().getMethod("isFree", String.class).invoke(pricing, "FREE");

    assertThat(RecordingProbes.VALUES).isEmpty();
    assertThat(RecordingProbes.LINES).isNotEmpty();
  }

  @Test
  void aStaticInitialiserSaysWhenItStartsAndReturns() throws Exception {
    loadedRates();

    assertThat(RecordingProbes.CALLS.getFirst()).isEqualTo("loading");
    assertThat(RecordingProbes.CALLS.getLast()).isEqualTo("loaded");
    assertThat(RecordingProbes.CALLS).filteredOn("loaded"::equals).hasSize(1);
  }

  @Test
  void aLineRunOnlyAtLoadIsCoveredByEveryTestThatUsesItsClass() throws Exception {
    var rates = loadedRates();
    var atLoad = new HashSet<>(RecordingProbes.LINES);
    RecordingProbes.LINES.clear();
    rates.getMethod("of", String.class).invoke(null, "gold");
    var inUse = new HashSet<>(RecordingProbes.LINES);
    coverage.recordLines("RatesTest#loads", atLoad);
    coverage.recordLines("RatesTest#uses", inUse);
    coverage.recordLines("PricingTest#elsewhere", Set.of());
    coverage.recordOnlyAtLoad(atLoad);
    List.of("RatesTest#loads", "RatesTest#uses", "PricingTest#elsewhere")
        .forEach(test -> coverage.recordTest(test, Duration.ofMillis(1)));

    var inParse = rates("parse", "(Ljava/lang/String;)Ljava/util/Map;", 19);
    var inOf = rates("of", "(Ljava/lang/String;)I", 13);

    assertThat(coverage.onlyAtLoad(inParse)).isTrue();
    assertThat(coverage.coveringTests(inParse))
        .containsExactly("RatesTest#loads", "RatesTest#uses");
    assertThat(coverage.onlyAtLoad(inOf)).isFalse();
    assertThat(coverage.coveringTests(inOf)).containsExactly("RatesTest#uses");
  }

  /// Rates, instrumented and loaded, with only its own calls recorded.
  private Class<?> loadedRates() throws Exception {
    var bytes = coverage.instrument(MutationsTest.model(Rates.class));
    RecordingProbes.LINES.clear();
    RecordingProbes.CALLS.clear();
    assertThat(ClassFile.of().verify(bytes)).isEmpty();
    return Class.forName(
        Rates.class.getName(),
        true,
        new MutationsTest.SingleClassLoader(Rates.class.getName(), bytes));
  }

  private static Mutant rates(String method, String descriptor, int line) {
    return new Mutant(
        "io/mutastic/engine/fixture/Rates",
        method,
        descriptor,
        0,
        line,
        BuiltIn.MATH,
        "IADD",
        "ISUB",
        Optional.empty());
  }
}
