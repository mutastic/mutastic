package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What mutastic makes of real Lombok output (`samples/lombok`, Lombok as most projects have it, no
/// `lombok.config`): what Lombok wrote is skipped, the person's own methods run, and the null
/// checks they asked for with `@NonNull` run, where a test that passes `null` catches them.
class LombokTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  /// The methods the person wrote: everything else in the sample is Lombok's.
  private static final Set<String> THEIRS =
      Set.of(
          "tier", "plus", "times", "isZero", "subtotal", "total", "discounted", "write", "totalIn");

  /// The lines of `Receipts` with a `@Cleanup` on them.
  private static final Set<Integer> CLEANUPS = Set.of(18, 26);

  @TempDir Path work;

  /// What these tests read of a `mutant-finished` event.
  private record Seen(
      String id, String status, int line, String method, String bytecode, String reason) {
    private static final Pattern EVENT =
        Pattern.compile(
            "\"id\":\"(?<id>[^\"]+)\".*\"status\":\"(?<status>\\w+)\".*\"line\":(?<line>\\d+)"
                + ".*\"method\":\"(?<method>[^\"(]+)\\(.*\"level\":\"bytecode\",\"text\":\"(?<bytecode>[^\"]*)\""
                + "(?:.*\"statusReason\":\"(?<reason>[^\"]*)\")?");

    static Seen of(String event) {
      var at = EVENT.matcher(event);
      assertThat(at.find()).as(event).isTrue();
      return new Seen(
          at.group("id"),
          at.group("status"),
          Integer.parseInt(at.group("line")),
          at.group("method"),
          at.group("bytecode"),
          at.group("reason") == null ? "" : at.group("reason"));
    }

    boolean ran() {
      return !status.equals("Ignored");
    }
  }

  @Test
  void whatLombokWroteIsSkippedAndWhatThePersonAskedForRuns() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("lombok"), work.resolve("lombok"));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--operators=stronger", "--format=jsonl", "--report=none");
    var mutants =
        run.out()
            .lines()
            .filter(event -> event.contains("\"reason\":\"mutant-finished\""))
            .map(Seen::of)
            .toList();

    // Lombok's members: skipped, but for the check `@NonNull` asks for in a setter.
    var lomboks = mutants.stream().filter(mutant -> !THEIRS.contains(mutant.method())).toList();
    assertThat(lomboks).as(run.err()).hasSizeGreaterThan(100);
    assertThat(lomboks)
        .filteredOn(Seen::ran)
        .isNotEmpty()
        .allSatisfy(
            mutant -> {
              assertThat(mutant.method()).isEqualTo("setGiftMessage");
              assertThat(mutant.bytecode()).startsWith("IFNONNULL");
            });
    // A `@Cleanup`'s close: skipped. The rest of the person's code runs.
    var theirs = mutants.stream().filter(mutant -> THEIRS.contains(mutant.method())).toList();
    assertThat(theirs)
        .filteredOn(mutant -> mutant.reason().equals("generated: closes a `@Cleanup` resource"))
        .hasSizeGreaterThanOrEqualTo(8)
        .allSatisfy(mutant -> assertThat(CLEANUPS).contains(mutant.line()));
    assertThat(theirs)
        .filteredOn(mutant -> mutant.ran() && CLEANUPS.contains(mutant.line()))
        .allSatisfy(
            mutant ->
                assertThat(mutant.bytecode())
                    .doesNotContain("singletonList", "List.get", "close", "ICONST_0"));
    assertThat(theirs).filteredOn(mutant -> mutant.reason().startsWith("generated: @")).isEmpty();

    // The cards say no test passes `null`: one that does catches each check.
    var checks =
        mutants.stream()
            .filter(mutant -> mutant.ran() && mutant.bytecode().equals("IFNONNULL -> always jump"))
            .toList();
    assertThat(checks)
        .extracting(Seen::method, Seen::status)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("write", "Survived"),
            org.assertj.core.groups.Tuple.tuple("setGiftMessage", "Survived"));
    passNull(project);
    var ids = checks.stream().map(Seen::id).toList();
    var verified = Samples.mutastic("verify", ids.get(0), ids.get(1), project.toString());
    assertThat(verified.out())
        .as(verified.err())
        .contains(
            "is caught by com.example.orders.NullTest#aReceiptNeedsAnOrder",
            "is caught by com.example.orders.NullTest#aGiftMessageIsNeverNull");
  }

  /// Tests that pass `null` where the sample says `@NonNull`, and assert what then happens.
  private static void passNull(Path project) throws Exception {
    Files.writeString(
        project.resolve("src/test/java/com/example/orders/NullTest.java"),
        """
        package com.example.orders;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertThrows;

        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.io.TempDir;

        class NullTest {
          @Test
          void aReceiptNeedsAnOrder(@TempDir Path dir) {
            var thrown =
                assertThrows(
                    NullPointerException.class,
                    () -> Receipts.write(null, dir.resolve("receipt.txt")));
            assertEquals("order is marked non-null but is null", thrown.getMessage());
          }

          @Test
          void aGiftMessageIsNeverNull() {
            var line = new OrderLine("tea", new Money(450, "EUR"));
            assertThrows(NullPointerException.class, () -> line.setGiftMessage(null));
          }
        }
        """);
  }
}
