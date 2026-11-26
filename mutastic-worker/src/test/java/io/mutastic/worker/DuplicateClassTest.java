package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DuplicateClassTest {
  private static final String SAID =
      "loader io.mutastic.worker.ProductionLoader @64c64813 attempted duplicate class definition"
          + " for demo.Prices$$EnhancerBySpringCGLIB$$8f1d6f1a.";

  @Test
  void aClassDefinedTwiceIsFoundAmongTheCauses() {
    var context =
        new IllegalStateException(
            "Failed to load ApplicationContext",
            new RuntimeException("bean", new LinkageError(SAID)));

    assertThat(DuplicateClass.in(context)).isTrue();
  }

  @Test
  void andAmongWhatTheySuppressed() {
    var failure = new AssertionError("expected 3");
    failure.addSuppressed(new LinkageError(SAID));

    assertThat(DuplicateClass.in(failure)).isTrue();
  }

  @Test
  void anotherLinkageErrorOrMessageIsnt() {
    assertThat(DuplicateClass.in(new LinkageError("loader constraint violation"))).isFalse();
    assertThat(DuplicateClass.in(new IllegalStateException("duplicate class definition")))
        .isFalse();
    assertThat(DuplicateClass.in(new LinkageError(null))).isFalse();
  }

  @Test
  void aChainThatLoopsBackStillEnds() {
    var outer = new RuntimeException("outer");
    var inner = new RuntimeException("inner", outer);
    outer.initCause(inner);

    assertThat(DuplicateClass.in(outer)).isFalse();
  }
}
