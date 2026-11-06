package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.lang.classfile.ClassFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A class compiled for Java 8 is mutated as Java 8: its mutants keep class-file version 52,
/// verify, and change what it does, as the spike checked of an engine on a newer JDK.
class ClassVersionsTest {
  @Test
  void java8BytecodeIsMutatedAsJava8() throws Exception {
    var source =
        new Javac.Source(
            "legacy.Fees",
            """
            package legacy;

            public class Fees {
              public int fee(int days) {
                if (days >= 30) {
                  return 25;
                }
                return 0;
              }
            }
            """);
    var bytes = Javac.compile(List.of(source), List.of(), "--release", "8").get("legacy.Fees");
    var mutations = new Mutations(ClassFile.of());
    var model = ClassFile.of().parse(bytes);

    var boundary =
        mutations.find(model).stream()
            .filter(mutant -> mutant.operator() == BuiltIn.BOUNDARY)
            .findFirst()
            .orElseThrow();
    var mutated = mutations.apply(model, boundary);

    assertThat(ClassFile.of().parse(mutated).majorVersion()).isEqualTo(52);
    for (Mutant mutant : mutations.find(model)) {
      assertThat(mutations.verify(mutations.apply(model, mutant))).as(mutant.toString()).isEmpty();
    }
    var fees =
        new MutationsTest.SingleClassLoader("legacy.Fees", mutated)
            .loadClass("legacy.Fees")
            .getConstructor()
            .newInstance();
    assertThat(fees.getClass().getMethod("fee", int.class).invoke(fees, 30)).isEqualTo(0); // was 25
  }
}
