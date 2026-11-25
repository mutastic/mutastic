package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Which test classes can see state an earlier run left, for each shape a test keeps it in, and
/// those that keep none.
class StaleStateTest {
  private static final String SHAPES =
      """
      package shapes;

      import java.util.ArrayList;
      import java.util.List;

      class CountedTest {
        static int runs;
      }

      class RememberedTest {
        private static Integer remembered;

        void remember(int value) {
          if (remembered == null) {
            remembered = value;
          }
        }
      }

      class CollectedTest {
        static final List<String> SEEN = new ArrayList<>();
      }

      class Registry {
        int registered;
      }

      class RegisteredTest {
        static final Registry REGISTRY = new Registry();
      }

      class HeldTest {
        static final Object HELD = new int[1];
      }

      class OuterTest {
        class Twice {
          static int runs;
        }
      }

      class ReachesAHelperTest {
        int count() {
          return Registry.class.hashCode() + RegisteredTest.REGISTRY.registered;
        }
      }

      final class Money {
        final long amount;

        Money(long amount) {
          this.amount = amount;
        }
      }

      class PlainTest {
        static final String NAME = "plain";
        static final Money PRICE = new Money(2);
        static final long[] NONE = null;
      }

      class AloneTest {
        static final String NAME = "alone";
        static final Money PRICE = new Money(3);
      }
      """;

  private static StaleState state;

  @BeforeAll
  static void scan() throws Exception {
    var compiled =
        Javac.compile(List.of(new Javac.Source("shapes.Shapes", SHAPES)), List.of(), "-g");
    var models = new HashMap<String, ClassModel>();
    compiled.forEach(
        (name, bytes) -> models.put(name.replace('.', '/'), ClassFile.of().parse(bytes)));
    state = StaleState.of(models);
  }

  @Test
  void aStaticThatChangesIsState() {
    assertThat(state.reaches("shapes.CountedTest#runsOnce()")).isTrue();
    assertThat(state.reaches("shapes.RememberedTest#saysTheSame()")).isTrue();
  }

  @Test
  void aStaticFinalHoldingWhatCanChangeIsState() {
    assertThat(state.reaches("shapes.CollectedTest#seesOne()")).isTrue();
    assertThat(state.reaches("shapes.RegisteredTest#registersOnce()")).isTrue();
    assertThat(state.reaches("shapes.HeldTest#holdsOne()")).isTrue();
  }

  @Test
  void aNestedClassAndItsOuterClassShareWhatEitherKeeps() {
    assertThat(state.reaches("shapes.OuterTest$Twice#runsOnce()")).isTrue();
    assertThat(state.reaches("shapes.OuterTest")).isTrue();
  }

  @Test
  void aTestThatRefersToStateReachesIt() {
    assertThat(state.reaches("shapes.ReachesAHelperTest#counts()")).isTrue();
  }

  /// Strings, immutable test-source values, a final array field left null on its own class (never
  /// read here): the plain test's array makes it state, the one with none is clean.
  @Test
  void constantsAndImmutableValuesAreNoState() {
    assertThat(state.reaches("shapes.AloneTest#plain()")).isFalse();
    assertThat(state.reaches("shapes.PlainTest#plain()")).isTrue();
  }

  @Test
  void aClassOutsideTheTestsIsNoState() {
    assertThat(state.reaches("com.example.Elsewhere#x()")).isFalse();
    assertThat(StaleState.of(Map.of()).reaches("shapes.CountedTest")).isFalse();
  }
}
