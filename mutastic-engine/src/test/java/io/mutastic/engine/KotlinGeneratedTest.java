package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Skip;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What kotlinc wrote, told from what the source did by what's in the class file, with Java
/// stand-ins for its marker and its checks: `samples/kotlin` has the real output, in the CLI's
/// end-to-end test.
class KotlinGeneratedTest {
  private static final Javac.Source METADATA =
      new Javac.Source(
          "kotlin.Metadata",
          """
          package kotlin;
          import java.lang.annotation.*;
          @Retention(RetentionPolicy.RUNTIME) public @interface Metadata {}
          """);
  private static final Javac.Source INTRINSICS =
      new Javac.Source(
          "kotlin.jvm.internal.Intrinsics",
          """
          package kotlin.jvm.internal;
          public final class Intrinsics {
            public static void checkNotNullParameter(Object value, String name) {}
            public static void checkNotNull(Object value) {}
            public static void throwUninitializedPropertyAccessException(String name) {}
            public static boolean areEqual(Object a, Object b) { return a == b; }
          }
          """);
  private final KotlinGenerated filter = new KotlinGenerated();

  private static ClassModel compiled(String name, String source, String... options)
      throws Exception {
    var classes =
        Javac.compile(
            List.of(new Javac.Source(name, source)), List.of(METADATA, INTRINSICS), options);
    return ClassFile.of().parse(classes.get(name));
  }

  private static MethodModel method(ClassModel model, String name) {
    return model.methods().stream()
        .filter(method -> method.methodName().equalsString(name))
        .findFirst()
        .orElseThrow();
  }

  private static final String WITH_MARKER =
      """
      package fixture;
      @kotlin.Metadata
      public class Item {
        public int one() { return 1; }
      }
      """;

  @Test
  void aMethodOfAKotlinClassWithNoLinesIsGenerated() throws Exception {
    var model = compiled("fixture.Item", WITH_MARKER, "-g:none");

    assertThat(filter.skip(model, method(model, "one")))
        .hasValueSatisfying(skip -> assertThat(skip.kind()).isEqualTo(Skip.Kind.GENERATED));
  }

  @Test
  void aMethodWithLinesIsTheSources() throws Exception {
    var model = compiled("fixture.Item", WITH_MARKER);

    assertThat(filter.skip(model, method(model, "one"))).isEmpty();
  }

  @Test
  void aJavaMethodWithNoLinesIsJustCompiledWithoutThem() throws Exception {
    var model =
        compiled(
            "fixture.Plain",
            """
            package fixture;
            public class Plain {
              public int one() { return 1; }
            }
            """,
            "-g:none");

    assertThat(filter.skip(model, method(model, "one"))).isEmpty();
  }

  @Test
  void anAbstractMethodHasNothingToSkip() throws Exception {
    var model =
        compiled(
            "fixture.Shape",
            """
            package fixture;
            @kotlin.Metadata
            public abstract class Shape {
              public abstract int one();
            }
            """);

    assertThat(filter.skip(model, method(model, "one"))).isEmpty();
  }

  @Test
  void itsChecksAreInsertedAndEqualityIsTheSources() throws Exception {
    var model =
        compiled(
            "fixture.Calls",
            """
            package fixture;
            import kotlin.jvm.internal.Intrinsics;
            public class Calls {
              public boolean run(String a) {
                Intrinsics.checkNotNullParameter(a, "a");
                Intrinsics.throwUninitializedPropertyAccessException("a");
                Intrinsics.checkNotNull(a);
                a.length();
                return Intrinsics.areEqual(a, a);
              }
            }
            """);

    var inserted =
        method(model, "run").code().orElseThrow().elementList().stream()
            .filter(InvokeInstruction.class::isInstance)
            .map(element -> (InvokeInstruction) (Instruction) element)
            .filter(KotlinChecks::isInserted)
            .map(call -> call.name().stringValue())
            .toList();

    assertThat(inserted)
        .containsExactly("checkNotNullParameter", "throwUninitializedPropertyAccessException");
    assertThat(filter.id()).isEqualTo("mutastic:kotlin-generated");
  }
}
