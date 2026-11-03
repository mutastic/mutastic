package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A class compiled for Java 8 is mutated as Java 8: its mutants keep class-file version 52,
/// verify, and change what it does, as the spike checked of an engine on a newer JDK.
class ClassVersionsTest {
  @TempDir Path work;

  @Test
  void java8BytecodeIsMutatedAsJava8() throws Exception {
    var source = work.resolve("legacy/Fees.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
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
    int compiled =
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "--release", "8", "-d", work.toString(), source.toString());
    assertThat(compiled).isZero();
    var bytes = Files.readAllBytes(work.resolve("legacy/Fees.class"));
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
