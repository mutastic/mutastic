package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuildArgumentsTest {
  @Test
  void aVariableSplitsAtSpacesButNotInsideQuotes() {
    assertThat(BuildArguments.split("  -Pci --offline\t-Pa='b c' \"-Pd=it's\" '' C:\\x "))
        .containsExactly("-Pci", "--offline", "-Pa=b c", "-Pd=it's", "", "C:\\x");
    assertThat(BuildArguments.split(" ")).isEmpty();
    assertThatThrownBy(() -> BuildArguments.split("-Pa='b")).hasMessage("a ' isn't closed");
  }

  /// Only what was typed is repeated, quoted so a shell reads it back; a variable's or a file's
  /// applies by itself.
  @Test
  void onlyTypedArgumentsAreRepeated() {
    var typed = BuildArguments.typed(List.of("-Pa=b c", "$HOME", "it's", "<id>", ""));
    assertThat(typed.ending()).isEqualTo(" -- '-Pa=b c' '$HOME' 'it'\\''s' '<id>' ''");
    assertThat(typed.buildEnding()).isEqualTo(" '-Pa=b c' '$HOME' 'it'\\''s' '<id>' ''");
    assertThat(BuildArguments.typed(List.of()).ending()).isEqualTo(" --");

    var quiet = BuildArguments.quiet(List.of("-Ptoken=secret"));
    assertThat(quiet.words()).containsExactly("-Ptoken=secret");
    assertThat(quiet.ending() + quiet.buildEnding()).isEmpty();
  }
}
