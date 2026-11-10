package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A call to a composable lambda, `content(title)` on a `@Composable (String) -> Unit`, with Java
/// stand-ins for the runtime: kotlinc calls `Function3.invoke` with the argument, the `Composer`
/// and a boxed `$changed`. `ComposeTest` has the Compose compiler's real output.
class ComposableLambdaCallTest {
  private static final Javac.Source COMPOSER =
      new Javac.Source(
          "androidx.compose.runtime.Composer",
          """
          package androidx.compose.runtime;
          public interface Composer {}
          """);
  private static final Javac.Source FUNCTION3 =
      new Javac.Source(
          "kotlin.jvm.functions.Function3",
          """
          package kotlin.jvm.functions;
          public interface Function3<P1, P2, P3, R> { R invoke(P1 p1, P2 p2, P3 p3); }
          """);
  private static final Javac.Source SCREEN =
      new Javac.Source(
          "fixture.Screen",
          """
          package fixture;
          import androidx.compose.runtime.Composer;
          import kotlin.jvm.functions.Function3;
          public class Screen {
            static void Screen(
                Function3<Object, Object, Object, Object> content, Composer $composer, int $changed) {
              content.invoke("Title", $composer, 0);
            }
          }
          """);

  /// The `0` and its boxing are the Compose compiler's; the title is the person's.
  @Test
  void theChangedMaskGivenToAComposableLambdaIsTheCompilers() throws Exception {
    var classes = Javac.compile(List.of(SCREEN), List.of(COMPOSER, FUNCTION3));
    var model = ClassFile.of().parse(classes.get("fixture.Screen"));
    MethodModel screen =
        model.methods().stream()
            .filter(method -> method.methodName().equalsString("Screen"))
            .findFirst()
            .orElseThrow();
    var code = screen.code().orElseThrow().elementList();

    assertThat(Compose.machinery(screen, code))
        .contains(place(code, Opcode.ICONST_0), place(code, Opcode.INVOKESTATIC))
        .doesNotContain(place(code, Opcode.LDC));
  }

  private static int place(List<CodeElement> code, Opcode opcode) {
    var places = new ArrayList<Integer>();
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof Instruction instruction && instruction.opcode() == opcode) {
        places.add(at);
      }
    }
    assertThat(places).hasSize(1);
    return places.getFirst();
  }
}
