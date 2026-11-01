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

    assertThat(command).isEqualTo(new Main.Command(false, Path.of("."), ":", Optional.empty(), 80, Optional.empty(), false, Optional.empty(), "auto", "auto", "auto", true, Main.DEFAULT_REPORTS));
  }

  @Test
  void readsDirectoryModuleAndOptionsInAnyOrder() {
    var command = Main.Command.parse(new String[] {"run", ":api", "--format=jsonl", "../app", "--threshold=60"});

    assertThat(command).isEqualTo(new Main.Command(false, Path.of("../app"), ":api", Optional.of("jsonl"), 60, Optional.empty(), false, Optional.empty(), "auto", "auto", "auto", true, Main.DEFAULT_REPORTS));
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
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--workers=0"})).hasMessage("--workers must be at least 1");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--threshold=high"}))
        .hasMessage("--threshold needs a whole number, not high");
  }

  @Test
  void reportsReplaceTheDefaultsAndTakeAPath() {
    assertThat(Main.Command.parse(new String[] {"run", "--report=xml", "--report=json:out/m.json"}).reports()).containsExactly(
        new Main.RequestedReport("xml", Optional.empty()), new Main.RequestedReport("json", Optional.of(Path.of("out/m.json"))));
    assertThat(Main.Command.parse(new String[] {"run", "--report=none"}).reports()).isEmpty();
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--report=pdf"}))
        .hasMessage("unknown report pdf (one of json, html, xml, none)");
  }

  @Test
  void readsTheWorkerCount() {
    assertThat(Main.Command.parse(new String[] {"run", "--workers=3"}).workers()).contains(3);
  }
}
