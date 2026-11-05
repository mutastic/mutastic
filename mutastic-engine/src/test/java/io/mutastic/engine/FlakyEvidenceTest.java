package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.fixture.Changing;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlakyEvidenceTest {
  /// Only what answers differently each run: a seed, or a clock passed in, answers the same.
  @Test
  void findsWhatChangesFromRunToRunAndNotWhatsSeeded() throws Exception {
    assertThat(changingIn("unseeded")).containsExactly("new Random()", "ThreadLocalRandom.current");
    assertThat(changingIn("seeded")).isEmpty();
    assertThat(changingIn("times")).containsExactly("Instant.now", "System.nanoTime");
    assertThat(changingIn("ids"))
        .containsExactly("Collections.shuffle", "UUID.randomUUID", "Math.random");
  }

  private static List<String> changingIn(String method) throws Exception {
    var bytes = Changing.class.getResourceAsStream("Changing.class").readAllBytes();
    return ClassFile.of().parse(bytes).methods().stream()
        .filter(candidate -> candidate.methodName().equalsString(method))
        .flatMap(candidate -> candidate.code().stream())
        .flatMap(code -> code.elementStream())
        .filter(InvokeInstruction.class::isInstance)
        .map(element -> FlakyEvidence.changing((InvokeInstruction) element))
        .flatMap(java.util.Optional::stream)
        .toList();
  }
}
