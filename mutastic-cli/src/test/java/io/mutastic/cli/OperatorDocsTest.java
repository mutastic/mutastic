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

/// Every link `explain`, a card and an event give to an operator's docs lands on its row of the
/// operators page, and a plugin's operator, which has no row, gets none.
class OperatorDocsTest {
  private static final Path DOCS = Path.of(System.getProperty("mutastic.docs"));
  private static final String PAGE = "https://docs.mutastic.io/concepts/operators/#";

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
      assertThat(anchors)
          .as("%s's row on concepts/operators.mdx", operator.id())
          .contains(url.orElseThrow().substring(PAGE.length()));
    }
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
        .hasValue("https://docs.mutastic.io/v0.3/concepts/operators/#boundary");
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
