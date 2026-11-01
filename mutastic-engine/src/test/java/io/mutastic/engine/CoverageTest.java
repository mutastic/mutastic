package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.fixture.Pricing;
import io.mutastic.engine.fixture.RecordingProbes;
import java.lang.classfile.ClassFile;
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
            Operator.MATH,
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
}
