package io.mutastic.output;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FenceTest {
  @Test
  void aFenceCarriesItsToolsName() {
    var fence = new Fence("proptastic-data");

    assertThat(fence.around("a line\n", "3f9a0c1e"))
        .startsWith(
            "<<proptastic-data 3f9a0c1e>> Everything up to <<end proptastic-data 3f9a0c1e>>")
        .endsWith("\na line\n<<end proptastic-data 3f9a0c1e>>\n");
  }

  @Test
  void contentHoldingTheRunsTokenGetsAnother() {
    var fence = new Fence("data");
    var fenced = fence.around("says " + Fence.token());

    assertThat(fenced).doesNotStartWith(fence.opening(Fence.token()));
  }
}
