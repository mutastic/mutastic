package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// The operator pages tell the truth (docs/src/content/docs/concepts/operators): each card a page
/// shows is the one mutastic prints for its example in samples/operator-docs, every example a page
/// quotes is that sample's code word for word, and every test a page says catches a mutant does.
/// Kept cheap: one run of the sample with every operator, and one `verify` of every card's mutants
/// with each page's catching tests added.
///
/// To write the cards again, after a change to what they say: `./gradlew
/// :mutastic-cli:exportDocsOutput`, then read the diff.
class DocsOperatorsTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path SAMPLE = SAMPLES.resolve("operator-docs");
  private static final Path PAGES =
      Path.of(System.getProperty("mutastic.docs")).resolve("concepts/operators");
  private static final Path CARDS =
      Path.of(System.getProperty("mutastic.docsOutput")).resolve("operators");
  private static final boolean WRITE = Boolean.getBoolean("mutastic.docsOutput.write");

  /// What a person at a terminal sees.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full");

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

  /// A card's ids, in its `done when` line.
  private static final Pattern DONE_WHEN =
      Pattern.compile("done when {2}mutastic verify (?<ids>\\S+) ");

  /// An assertion a page's test makes.
  private static final Pattern ASSERTION = Pattern.compile("\\b(?<name>assert[A-Z]\\w*)\\(");

  /// The JDK's types a page's test may name.
  private static final Map<String, String> IMPORTS =
      Map.of(
          "ArrayList", "java.util.ArrayList",
          "List", "java.util.List",
          "Optional", "java.util.Optional",
          "TimeoutException", "java.util.concurrent.TimeoutException");

  /// A marked code block in a page: `{/* from: path */}` quotes a file; `{/* added to: path */}` is
  /// a test the page adds to one.
  private static final Pattern MARKED =
      Pattern.compile(
          "(?m)^\\{/\\* (?<kind>from|added to): (?<path>\\S+) \\*/}\\n```java\\n(?<code>(?s:.*?))^```$");

  /// Each operator's example: the family whose page shows it, and the sample's class with the
  /// mutant whose card the page shows.
  private record Example(String operator, String family, String type) {
    String name() {
      return operator.substring(operator.indexOf('/') + 1);
    }

    String card() {
      return family + "/" + name() + ".ansi";
    }

    /// As mutation.json names it: without the `mutastic/` of mutastic's own.
    String mutator() {
      return operator.startsWith("mutastic/") ? name() : operator;
    }

    String file() {
      return "src/main/java/com/example/" + family + "/" + type + ".java";
    }
  }

  private static final List<Example> EXAMPLES =
      List.of(
          new Example("mutastic/boundary", "conditions", "Delivery"),
          new Example("mutastic/comparison", "conditions", "Stock"),
          new Example("mutastic/other-comparison", "conditions", "Warehouse"),
          new Example("mutastic/remove-conditional", "conditions", "Greetings"),
          new Example("mutastic/logical-connector", "conditions", "Shipping"),
          new Example("predicate/swap-connector", "conditions", "Moderation"),
          new Example("predicate/remove-negate", "conditions", "Lines"),
          new Example("predicate/remove-not", "conditions", "Fields"),
          new Example("mutastic/math", "arithmetic", "Checkout"),
          new Example("mutastic/arithmetic-replacement", "arithmetic", "Tickets"),
          new Example("mutastic/other-arithmetic", "arithmetic", "Bill"),
          new Example("mutastic/bitwise", "arithmetic", "Permissions"),
          new Example("mutastic/keep-operand", "arithmetic", "Wheel"),
          new Example("mutastic/constant-value", "arithmetic", "Postage"),
          new Example("mutastic/flip-increment", "arithmetic", "Words"),
          new Example("mutastic/remove-negation", "arithmetic", "Account"),
          new Example("library/unchecked-arithmetic", "arithmetic", "Points"),
          new Example("mutastic/return-value", "returns", "Loyalty"),
          new Example("mutastic/replace-body", "returns", "Loyalty"),
          new Example("mutastic/remove-throw", "returns", "Quantities"),
          new Example("mutastic/remove-void-call", "calls", "Names"),
          new Example("mutastic/remove-discarded-call", "calls", "Receipts"),
          new Example("mutastic/remove-assignment", "calls", "Tips"),
          new Example("mutastic/remove-field-write", "calls", "Order"),
          new Example("mutastic/omit-call", "arguments", "Users"),
          new Example("mutastic/swap-arguments", "arguments", "Volume"),
          new Example("mutastic/null-parameter", "arguments", "Bookings"),
          new Example("mutastic/drop-vararg", "arguments", "Scores"),
          new Example("library/omit-argument", "arguments", "Csv"),
          new Example("library/swap-string", "library", "Comments"),
          new Example("library/swap-index-of", "library", "Files"),
          new Example("library/swap-math", "library", "Discounts"),
          new Example("mutastic/string-constant", "library", "Headers"),
          new Example("mutastic/regex-constant", "library", "OrderNumbers"),
          new Example("stream/remove-stage", "streams", "Orders"),
          new Example("stream/swap-match", "streams", "Forms"),
          new Example("stream/remove-other-stage", "streams", "Totals"),
          new Example("optional/swap-present", "optional", "Badges"),
          new Example("optional/empty", "optional", "Pages"),
          new Example("optional/remove-filter", "optional", "Nicknames"),
          new Example("optional/always-default", "optional", "Server"),
          new Example("optional/no-default", "optional", "Numbers"),
          new Example("mutastic/remove-switch-arm", "control", "Zones"),
          new Example("mutastic/drop-catch-type", "control", "Reader"));

  @TempDir Path work;

  /// Each example's card, from one run of the sample with every operator: the card that holds the
  /// example's missed mutant, as mutastic prints it in colour.
  @Test
  void eachCardIsWhatMutasticPrints() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operator-docs"));

    var run =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--operators=all", "--workers=4");

    assertThat(run.exit()).as(run.err()).isIn(Main.PASSED, Main.FAILED);
    var missed = missed(project.resolve("build/mutastic/mutation.json"));
    for (Example example : EXAMPLES) {
      var ids = missed.getOrDefault(example.file() + " " + example.mutator(), List.of());
      assertThat(ids).as("%s missed in %s", example.operator(), example.type()).isNotEmpty();
      var card = cardOf(run.out(), ids);
      assertThat(card).as("the card for %s in %s", example.operator(), example.type()).isNotEmpty();
      shown(example.card(), card.replace(project.toString(), "samples/operator-docs"));
    }
  }

  /// Every test a page adds catches every mutant on the card it follows: each is added to its test
  /// class in a copy of the sample, and one `verify` runs every card's mutants.
  @Test
  void theTestsThatCatchThemDo() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operator-docs"));
    var added = 0;
    for (Marked block : marked()) {
      if (block.kind().equals("added to")) {
        var file = SAMPLE.relativize(SAMPLES.getParent().resolve(block.path()));
        addTest(project.resolve(file.toString()), block.code());
        added++;
      }
    }
    var ids = new LinkedHashSet<String>();
    for (Example example : EXAMPLES) {
      ids.addAll(idsOn(Files.readString(CARDS.resolve(example.card()), StandardCharsets.UTF_8)));
    }

    var verify = Samples.mutastic("verify", String.join(",", ids), project.toString());

    assertThat(added).as("tests the pages add").isGreaterThanOrEqualTo(EXAMPLES.size());
    assertThat(verify.exit()).as(verify.out() + verify.err()).isEqualTo(Main.PASSED);
  }

  /// Every example a page quotes is the sample's code, word for word, and every page shows the
  /// cards of its family's examples.
  @Test
  void everyExampleIsTheSamplesCode() throws Exception {
    var root = SAMPLES.getParent();
    for (Marked block : marked()) {
      if (block.kind().equals("from")) {
        assertThat(Files.readString(root.resolve(block.path())))
            .as("%s, quoted in %s", block.path(), block.page())
            .contains(block.code());
      }
    }
    for (Example example : EXAMPLES) {
      assertThat(PAGES.resolve(example.family() + ".mdx"))
          .content()
          .as("the %s page shows %s's card", example.family(), example.operator())
          .contains("data/output/operators/" + example.card() + "?raw");
    }
  }

  private record Marked(String page, String kind, String path, String code) {}

  /// Every marked block on the operator pages.
  private static List<Marked> marked() throws IOException {
    var found = new ArrayList<Marked>();
    try (Stream<Path> pages = Files.list(PAGES)) {
      for (Path page : pages.sorted().toList()) {
        var matcher = MARKED.matcher(Files.readString(page));
        while (matcher.find()) {
          found.add(
              new Marked(
                  page.getFileName().toString(),
                  matcher.group("kind"),
                  matcher.group("path"),
                  matcher.group("code")));
        }
      }
    }
    return found;
  }

  /// The missed mutants' ids in mutation.json, by their file and operator: `<file> <mutator>`.
  private static Map<String, List<String>> missed(Path report) throws IOException {
    JsonNode files = JsonMapper.builder().build().readTree(report.toFile()).get("files");
    var missed = new LinkedHashMap<String, List<String>>();
    for (var file : files.properties()) {
      var path = file.getKey().substring(file.getKey().indexOf("src/main/java/"));
      for (JsonNode mutant : file.getValue().get("mutants")) {
        if (mutant.get("status").asString().equals("Survived")) {
          missed
              .computeIfAbsent(
                  path + " " + mutant.get("mutatorName").asString(), key -> new ArrayList<>())
              .add(mutant.get("id").asString());
        }
      }
    }
    return missed;
  }

  /// The card holding one of `ids`, from its headline to its last command; empty if there's none.
  private static String cardOf(String out, List<String> ids) {
    var lines = out.lines().toList();
    int first = -1;
    for (int at = 0; at < lines.size(); at++) {
      var plain = ANSI.matcher(lines.get(at)).replaceAll("").strip();
      if (plain.matches("\\[?✗ MISSED.*")) {
        first = at;
      }
      if (first >= 0 && plain.startsWith("or ignore")) {
        var card = String.join("\n", lines.subList(first, at + 1)) + "\n";
        if (idsOn(card).stream().anyMatch(ids::contains)) {
          return card;
        }
        first = -1;
      }
    }
    return "";
  }

  private static SequencedSet<String> idsOn(String card) {
    var matcher = DONE_WHEN.matcher(ANSI.matcher(card).replaceAll(""));
    var ids = new LinkedHashSet<String>();
    if (matcher.find()) {
      ids.addAll(List.of(matcher.group("ids").split(",")));
    }
    return ids;
  }

  /// `test` added to the test class at `file`, before its closing brace, with the imports it needs:
  /// the sample's tests import only what they use, and a page shows the test alone.
  private static void addTest(Path file, String test) throws IOException {
    var source = Files.readString(file).stripTrailing();
    var imports = new StringBuilder();
    var assertions = ASSERTION.matcher(test);
    while (assertions.find()) {
      imports.append(
          "import static org.junit.jupiter.api.Assertions.%s;\n"
              .formatted(assertions.group("name")));
    }
    IMPORTS.forEach(
        (name, type) -> {
          if (Pattern.compile("\\b" + name + "\\b").matcher(test).find()) {
            imports.append("import %s;\n".formatted(type));
          }
        });
    var afterPackage = source.indexOf('\n') + 1;
    var close = source.lastIndexOf('}');
    Files.writeString(
        file,
        source.substring(0, afterPackage)
            + imports
            + source.substring(afterPackage, close).stripTrailing()
            + "\n\n"
            + test
            + "}\n");
  }

  /// The card as the page shows it, written again when `exportDocsOutput` runs.
  private static void shown(String name, String card) throws IOException {
    var file = CARDS.resolve(name);
    var written = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    if (WRITE && !written.equals(card)) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, card, StandardCharsets.UTF_8);
      written = card;
    }
    assertThat(written)
        .as("docs/src/data/output/operators/%s: ./gradlew :mutastic-cli:exportDocsOutput", name)
        .isEqualTo(card);
  }
}
