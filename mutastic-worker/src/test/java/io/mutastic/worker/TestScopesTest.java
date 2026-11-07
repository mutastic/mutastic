package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.mutastic.worker.fixture.Bystander;
import io.mutastic.worker.fixture.ClassSetup;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class TestScopesTest {
  private static final String CLASS = ClassSetup.class.getName();
  private static final String FIRST = CLASS + "#first";
  private static final String SECOND = CLASS + "#second";
  private static final String PARAMETERISED = CLASS + "#parameterised(int)";
  private static final String NESTED = ClassSetup.Inner.class.getName() + "#nested";
  private static final String BYSTANDER = Bystander.class.getName() + "#reachesNothing";

  /// Run once, as a class's static initialiser runs once in a JVM.
  private static final Map<String, Set<Integer>> LINES = linesByTestOverBoth();

  /// Each place's lines, by the tests they count for. The class's setup, teardown and static
  /// initialiser count for all its tests, its nested ones too; a `@MethodSource` for its own test;
  /// and a `@BeforeEach` for those it runs before. The class run before it gets none of it.
  @Test
  void whatAClassRunsCountsForEveryTestInItAndNoOtherClass() {
    var lines = LINES;

    var classLevel =
        Set.of(ClassSetup.STATIC_INITIALISER, ClassSetup.BEFORE_ALL, ClassSetup.AFTER_ALL);
    assertThat(lines.get(FIRST))
        .containsAll(classLevel)
        .contains(ClassSetup.BEFORE_EACH, ClassSetup.FIRST)
        .doesNotContain(ClassSetup.SECOND, ClassSetup.FACTORY, ClassSetup.NESTED);
    assertThat(lines.get(SECOND))
        .containsAll(classLevel)
        .contains(ClassSetup.SECOND)
        .doesNotContain(ClassSetup.FIRST);
    assertThat(lines.get(PARAMETERISED))
        .containsAll(classLevel)
        .contains(ClassSetup.FACTORY)
        .doesNotContain(ClassSetup.FIRST);
    assertThat(lines.get(NESTED))
        .containsAll(classLevel)
        .contains(ClassSetup.BEFORE_EACH, ClassSetup.NESTED_BEFORE_EACH, ClassSetup.NESTED);
    assertThat(lines.get(BYSTANDER)).isEmpty();
  }

  /// Nothing's left under a container's id, where the engine would find no test.
  @Test
  void everyLineEndsUpUnderATest() {
    assertThat(LINES)
        .allSatisfy(
            (test, lines) -> {
              if (!lines.isEmpty()) {
                assertThat(test).contains("#");
              }
            });
  }

  private static Map<String, Set<Integer>> linesByTestOverBoth() {
    Probes.reset();
    var scopes = new TestScopes(Worker::id, true);
    LauncherFactory.create()
        .execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Bystander.class), selectClass(ClassSetup.class))
                .build(),
            scopes);
    Probes.flush();
    scopes.credit();
    return Map.copyOf(Probes.linesByTest());
  }
}
