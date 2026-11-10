package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.ConstantInstruction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// What kotlinc passes in a call that leaves arguments to their defaults, with a Java stand-in for
/// its `f$default`: a mask for every 32 arguments, and a zero or a `null` for each argument a mask
/// marks. `samples/kotlin` and `samples/compose` have the real output, in the CLI's tests.
class KotlinDefaultsTest {
  private static final String SOURCE =
      """
      package fixture;
      public class Defaults {
        static int wide$default(long a0, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9, long a10, long a11, long a12, long a13, long a14, long a15, long a16, long a17, long a18, long a19, long a20, long a21, long a22, long a23, long a24, long a25, long a26, long a27, long a28, long a29, long a30, long a31, long a32, long a33, long a34, long a35, int mask0, int mask1, Object marker) {
          return 0;
        }
        static int narrow$default(int count, String name, int mask, Object marker) {
          return 0;
        }
        static int wide() {
          return wide$default(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, -1, 15, null);
        }
        static int narrow() {
          return narrow$default(5, null, 2, null);
        }
      }
      """;

  private static ClassModel model;

  @BeforeAll
  static void compile() throws Exception {
    var classes = Javac.compile(List.of(new Javac.Source("fixture.Defaults", SOURCE)), List.of());
    model = ClassFile.of().parse(classes.get("fixture.Defaults"));
  }

  /// 36 arguments left out take two masks: every stand-in, and both masks, are kotlinc's.
  @Test
  void everyMaskOfACallWithMoreThan32ArgumentsIsRead() {
    var code = code("wide");

    assertThat(KotlinDefaults.places(code)).containsExactlyInAnyOrderElementsOf(constants(code));
  }

  /// A mask with the second argument's bit set: its `null` is a stand-in, the `5` was passed.
  @Test
  void anArgumentPassedIsntAStandIn() {
    var code = code("narrow");
    var constants = constants(code);

    assertThat(KotlinDefaults.places(code))
        .containsExactlyInAnyOrder(constants.get(1), constants.get(2));
  }

  private static List<CodeElement> code(String method) {
    return model.methods().stream()
        .filter(each -> each.methodName().equalsString(method))
        .findFirst()
        .orElseThrow()
        .code()
        .orElseThrow()
        .elementList();
  }

  /// The places of the constants the call is given, in order, but its last: the marker's `null`.
  private static List<Integer> constants(List<CodeElement> code) {
    var places = new ArrayList<Integer>();
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof ConstantInstruction) {
        places.add(at);
      }
    }
    return places.subList(0, places.size() - 1);
  }
}
