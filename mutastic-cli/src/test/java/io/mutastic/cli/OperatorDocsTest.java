package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Operator;
import io.mutastic.engine.CallRules;
import io.mutastic.engine.OperatorSet;
import io.mutastic.report.OperatorDocs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Every link `explain`, a card, an event and SARIF give to an operator's docs lands on its section
/// of its family's page, by the edition's path, which the site redirects to the page, and a
/// plugin's operator, which has no section, gets none.
class OperatorDocsTest {
  private static final Path DOCS = Path.of(System.getProperty("mutastic.docs"));
  private static final String PAGE = "https://docs.mutastic.io/java/operators/";

  /// `java/operators/conditions/#boundary`: the family and the anchor.
  private static final Pattern LINK =
      Pattern.compile("(?<family>[a-z]+)/#(?<anchor>[a-z0-9-]+)");

  /// `## Boundary`: a section's heading, whose slug is its anchor.
  private static final Pattern HEADING = Pattern.compile("(?m)^## (?<heading>.+)$");

  /// `<span id="boundary"></span>`: a row's anchor.
  private static final Pattern ANCHOR = Pattern.compile("<span id=\"(?<anchor>[a-z0-9-]+)\">");

  @Test
  void everyOperatorMutasticShipsLinksToItsRowOnTheOperatorsPage() throws IOException {
    var anchors = anchors();
    var shipped = OperatorSet.available(List.of());

    for (Operator operator : shipped) {
      var url = OperatorDocs.LATEST.url(operator);
      assertThat(url).as(operator.id()).isPresent();
      assertThat(url.orElseThrow()).as(operator.id()).startsWith(PAGE);
      var link = LINK.matcher(url.orElseThrow().substring(PAGE.length()));
      assertThat(link.matches()).as(url.orElseThrow()).isTrue();
      assertThat(anchors)
          .as("%s's row on concepts/operators.mdx", operator.id())
          .contains(link.group("anchor"));
      assertThat(sections(link.group("family")))
          .as("%s's section on concepts/operators/%s.mdx", operator.id(), link.group("family"))
          .contains(link.group("anchor"));
    }
  }

  /// The site sends the edition's path to the page the Java edition's family pages are served at,
  /// as an HTTP redirect, which keeps the anchor.
  @Test
  void theSiteRedirectsTheEditionsPathToTheFamilyPages() throws IOException {
    var redirects = Files.readString(DOCS.resolve("../../../public/_redirects").normalize());

    assertThat(redirects.lines())
        .contains("/java/operators/* /concepts/operators/:splat 301")
        .contains("/:version/java/operators/* /:version/concepts/operators/:splat 301");
  }

  @Test
  void aPluginsOperatorHasNoLink() throws IOException {
    var plugins = DOCS.resolve("../../data/plugins").normalize();
    try (Stream<Path> files = Files.list(plugins)) {
      var operators = files.flatMap(file -> CallRules.read(file).stream()).toList();

      assertThat(operators).isNotEmpty();
      assertThat(operators).allSatisfy(op -> assertThat(OperatorDocs.LATEST.url(op)).isEmpty());
    }
  }

  @Test
  void aReleaseLinksToItsOwnDocs() {
    var boundary = OperatorSet.available(List.of()).getFirst();

    assertThat(new OperatorDocs(Help.docs("0.3.1")).url(boundary))
        .hasValue("https://docs.mutastic.io/v0.3/java/operators/conditions/#boundary");
  }

  /// The anchors of a family page's sections, as the site writes them from their headings:
  /// `## indexOf swap` is `#indexof-swap`.
  private static Set<String> sections(String family) throws IOException {
    var page = Files.readString(DOCS.resolve("concepts/operators/" + family + ".mdx"));
    return HEADING
        .matcher(page)
        .results()
        .map(found -> found.group("heading").strip().toLowerCase(java.util.Locale.ROOT))
        .map(heading -> heading.replace(' ', '-'))
        .collect(Collectors.toSet());
  }

  private static Set<String> anchors() throws IOException {
    var page = Files.readString(DOCS.resolve("concepts/operators.mdx"));
    return ANCHOR
        .matcher(page)
        .results()
        .map(found -> found.group("anchor"))
        .collect(Collectors.toSet());
  }
}
