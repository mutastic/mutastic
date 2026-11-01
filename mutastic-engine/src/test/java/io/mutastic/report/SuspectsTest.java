package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Operator;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SuspectsTest {
  private static final String TEST = "com.example.GameRejectionTest#occupiedCell";

  @Test
  void theSameExceptionInTheTestAcrossUnrelatedMethodsIsWorthANote() {
    var report = report(
        caught("GameService", "now", "java.lang.NullPointerException", "test"),
        caught("ApiError", "code", "java.lang.NullPointerException", "test"),
        caught("RulesEngine", "isLegalMove", "java.lang.NullPointerException", "test"));

    assertThat(Suspects.unrelatedCatches(report)).singleElement().satisfies(note -> {
      assertThat(note.test()).isEqualTo(TEST);
      assertThat(note.methods()).isEqualTo(3);
    });
  }

  @Test
  void assertionsAndCrashesInTheCodeAreOrdinaryCatches() {
    var report = report(
        caught("GameService", "now", "org.opentest4j.AssertionFailedError", "assertion"),
        caught("ApiError", "code", "java.lang.NullPointerException", "code"),
        caught("RulesEngine", "isLegalMove", "java.lang.NullPointerException", "test"));

    assertThat(Suspects.unrelatedCatches(report)).isEmpty();
  }

  @Test
  void manyCatchesInOneMethodAreOrdinary() {
    var report = report(
        caught("RulesEngine", "isLegalMove", "java.lang.NullPointerException", "test"),
        caught("RulesEngine", "isLegalMove", "java.lang.NullPointerException", "test"),
        caught("RulesEngine", "isLegalMove", "java.lang.NullPointerException", "test"));

    assertThat(Suspects.unrelatedCatches(report)).isEmpty();
  }

  private static Report report(MutantResult... results) {
    return new Report("sample", 17, List.of(results), 10, List.of(), Duration.ZERO, 1, 1, Duration.ZERO);
  }

  private static int ordinal;

  private static MutantResult caught(String className, String method, String failure, String origin) {
    var mutant = new Mutant("com/example/" + className, method, "()I", ordinal++, 1, Operator.RETURN_DEFAULT, "IRETURN",
        "return default", Optional.empty());
    return new MutantResult(mutant, Status.CAUGHT, List.of(TEST), Optional.of(TEST), Optional.of(failure),
        Optional.of(origin), 1, Optional.empty(), Duration.ZERO);
  }
}
