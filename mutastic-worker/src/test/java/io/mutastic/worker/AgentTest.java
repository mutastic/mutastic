package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.worker.fixture.CounterTest;
import org.junit.jupiter.api.Test;

/// A class whose static initialiser failed can't be redefined, and none of its code runs again: the
/// agent leaves it as it is rather than stop the worker.
class AgentTest {
  @Test
  void aClassWhoseInitialiserFailedIsTold() throws Exception {
    var name = "io.mutastic.worker.fixture.UnsetSetting";
    assertThatThrownBy(() -> Class.forName(name, true, getClass().getClassLoader()))
        .isInstanceOf(ExceptionInInitializerError.class);
    var broken = Class.forName(name, false, getClass().getClassLoader());

    assertThat(Agent.failedToInitialise(broken)).isTrue();
    assertThat(Agent.failedToInitialise(CounterTest.class)).isFalse();
  }
}
