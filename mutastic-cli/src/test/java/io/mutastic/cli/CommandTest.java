package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandTest {
  @Test
  void runDefaultsToTheCurrentDirectoryAndRootProject() {
    var command = Main.Command.parse(new String[] {"run"});

    assertThat(command).isEqualTo(new Main.Command(false, Path.of("."), ":", Optional.empty(), 80, Optional.empty(), false, "auto", "auto"));
  }

  @Test
  void readsDirectoryModuleAndOptionsInAnyOrder() {
    var command = Main.Command.parse(new String[] {"run", ":api", "--format=jsonl", "../app", "--threshold=60"});

    assertThat(command).isEqualTo(new Main.Command(false, Path.of("../app"), ":api", Optional.of("jsonl"), 60, Optional.empty(), false, "auto", "auto"));
  }

  @Test
  void verifyTakesTheMutantIdFirst() {
    var command = Main.Command.parse(new String[] {"verify", "m855c8e3c", "samples/pricing"});

    assertThat(command.verify()).isTrue();
    assertThat(command.only()).contains("m855c8e3c");
    assertThat(command.directory()).isEqualTo(Path.of("samples/pricing"));
  }

  @Test
  void rejectsWhatItDoesntKnow() {
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--fast"})).hasMessage("unknown option --fast");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--format=xml"})).hasMessage("unknown format xml (one of full, concise, agent, jsonl)");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"verify"})).hasMessage("verify needs a mutant id");
  }
}
