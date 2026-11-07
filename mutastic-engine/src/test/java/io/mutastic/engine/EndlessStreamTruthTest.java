package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Taking `limit` off a stream that only it ends: the skip says it never ends, and running the
/// mutant shows it, as the fixture's sources burn a fuse rather than hang. A stream that ends
/// without it keeps its mutant, which runs and finishes.
class EndlessStreamTruthTest {
  private static final String NEVER_ENDS = "unviable: without `.limit(…)` the stream never ends";
  private static final String LIMIT = ".limit(J)";

  private static ClaimFixture fixture;
  private static List<Mutant> limits;

  @BeforeAll
  static void compile() throws Exception {
    fixture = new ClaimFixture("rules/Endless.java", "stream/remove-stage");
    limits =
        fixture.mutants().stream().filter(mutant -> mutant.original().contains(LIMIT)).toList();
  }

  @Test
  void whatOnlyTheLimitEndsIsSkippedAndWouldNeverEnd() throws Exception {
    for (var method :
        List.of(
            "iterated",
            "generated",
            "intsIterated",
            "intsGenerated",
            "longsIterated",
            "longsGenerated",
            "doublesIterated",
            "doublesGenerated",
            "throughAStage",
            "throughATypeChange",
            "throughAVariable",
            "aLimitWorkedOut",
            "aLimitAddedTo",
            "aLimitFromAnArray",
            "aLimitFromALength",
            "aLimitNegated",
            "aLimitIncremented",
            "aStageWorkedOutOnBothSides",
            "stagesAfterIt",
            "randomInts",
            "randomIntsInARange",
            "randomLongs",
            "randomLongsInARange",
            "randomDoubles",
            "randomDoublesInARange",
            "secureRandomInts",
            "threadLocalRandomInts",
            "splittableRandomInts",
            "randomGeneratorInts",
            "randomThroughAStage")) {
      var mutant = onlyLimitIn(method);
      assertThat(mutant.skipped()).as(method).contains(NEVER_ENDS);
      assertThat(fixture.outcomeOf(mutant, "abc", "b"))
          .as(method)
          .isEqualTo("threw IllegalStateException");
      assertThat(fixture.outcomeOfOriginal(method, "abc", "b"))
          .as(method)
          .doesNotStartWith("threw");
    }
    // Not run: nothing here pulls on the first, the second pulls past the fuse in batches, and the
    // third asks for an array of the size a random stream gives itself.
    assertThat(onlyLimitIn("handedToTheCaller").skipped()).contains(NEVER_ENDS);
    assertThat(onlyLimitIn("throughAParallelStage").skipped()).contains(NEVER_ENDS);
    assertThat(onlyLimitIn("randomCollected").skipped()).contains(NEVER_ENDS);
  }

  @Test
  void whatEndsWithoutTheLimitKeepsAMutantThatRuns() throws Exception {
    for (var method :
        List.of(
            "bounded",
            "intsBounded",
            "notFromHere",
            "matchedWithoutIt",
            "randomIntsSized",
            "randomLongsSizedInARange",
            "randomDoublesSized")) {
      var mutant = onlyLimitIn(method);
      assertThat(mutant.skipped()).as(method).isEmpty();
      assertThat(fixture.outcomeOf(mutant, "abc", "b"))
          .as(method)
          .isEqualTo(fixture.outcomeOfOriginal(method + "Claim", "abc", "b"))
          .isNotEqualTo(fixture.outcomeOfOriginal(method, "abc", "b"));
    }
  }

  @Test
  void whatElseEndsTheStreamOrCantBeSeenKeepsItsMutant() {
    assertThat(limits)
        .filteredOn(
            mutant ->
                List.of(
                        "limitedBefore",
                        "endedBefore",
                        "endedAfter",
                        "foundAfter",
                        "twoValues",
                        "aLimitWithABranch",
                        "aParameter")
                    .contains(mutant.methodName()))
        .hasSize(8)
        .allSatisfy(mutant -> assertThat(mutant.skipped()).as(mutant.methodName()).isEmpty());
  }

  private static Mutant onlyLimitIn(String method) {
    return limits.stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }
}
