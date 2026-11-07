package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.SourceDebugExtensionAttribute;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Reading kotlinc's map, on one it wrote: a stand-in file with a copy of the file's own inline
/// function at 55 and the standard library's from 56.
class SourceMapTest {
  private static final String SELF = "probe/ProbeKt";
  private static final String SMAP =
      """
      SMAP
      Probe.kt
      Kotlin
      *S Kotlin
      *F
      + 1 Probe.kt
      probe/ProbeKt
      + 2 _Collections.kt
      kotlin/collections/CollectionsKt___CollectionsKt
      + 3 fake.kt
      kotlin/jvm/internal/FakeKt
      *L
      1#1,54:1
      34#1:55
      774#2:56
      865#2,2:57
      1563#2:59
      1634#2,3:60
      1#3:63
      *S KotlinDebug
      *F
      + 1 Probe.kt
      probe/ProbeKt
      *L
      35#1:55
      36#1:56
      *E
      """;
  private final SourceMap map = SourceMap.parse(SELF, SMAP);

  @Test
  void aLineOfTheFileIsItsOwn() {
    assertThat(map.copied(1)).isEmpty();
    assertThat(map.copied(54)).isEmpty();
  }

  @Test
  void aCopyOfTheFilesOwnInlineFunctionIsThatLine() {
    assertThat(map.copied(55)).contains(new SourceMap.Place(SELF, "Probe.kt", 34));
  }

  @Test
  void aRangeCountsFromItsFirstLine() {
    var library = "kotlin/collections/CollectionsKt___CollectionsKt";
    assertThat(map.copied(58)).contains(new SourceMap.Place(library, "_Collections.kt", 866));
    assertThat(map.copied(62)).contains(new SourceMap.Place(library, "_Collections.kt", 1636));
    assertThat(map.copied(58).orElseThrow().path()).isEqualTo("kotlin/collections/_Collections.kt");
  }

  @Test
  void aRangeWithNoFileIsInTheFileOfTheOneBefore() {
    var map =
        SourceMap.parse("A", "*S Kotlin\n*F\n+ 1 A.kt\nA\n+ 2 B.kt\nB\n*L\n7#2:20\n8:21\n*E\n");

    assertThat(map.copied(21)).contains(new SourceMap.Place("B", "B.kt", 8));
  }

  @Test
  void aStepSpreadsALineOverSeveral() {
    var map = SourceMap.parse("A", "*S Kotlin\n*F\n+ 1 B.kt\nB\n*L\n7#1,2:20,3\n*E\n");

    assertThat(map.copied(25)).contains(new SourceMap.Place("B", "B.kt", 12));
    assertThat(map.copied(26)).isEmpty();
  }

  @Test
  void itSaysWhetherAClassHoldsACopyOfALine() {
    assertThat(map.holdsCopyOf(SELF, 34)).isTrue();
    assertThat(map.holdsCopyOf(SELF, 35)).isFalse();
    assertThat(map.holdsCopyOf("probe/Other", 34)).isFalse();
  }

  @Test
  void aMapOfAnotherStratumIsNoMap() {
    assertThat(
            SourceMap.parse("A", "SMAP\nA.jsp\nJSP\n*S JSP\n*F\n1 A.jsp\n*L\n1,5:9\n*E\n")
                .copied(9))
        .isEmpty();
  }

  /// Java's own JSR-45 users (a JSP's class) and a class with this very text but not from kotlinc
  /// keep their lines, and so their mutants as they were: only `@kotlin.Metadata` turns this on.
  @Test
  void aClassKotlincDidntCompileKeepsItsLines() throws Exception {
    var name = "fixture.Plain";
    var classes =
        Javac.compile(
            List.of(
                new Javac.Source(
                    name,
                    "package fixture; public class Plain { public int one() { return 1; } }")),
            List.of());
    var mapped =
        ClassFile.of()
            .transformClass(
                ClassFile.of().parse(classes.get(name)),
                ClassTransform.endHandler(
                    builder ->
                        builder.with(
                            SourceDebugExtensionAttribute.of(
                                SMAP.getBytes(StandardCharsets.UTF_8)))));

    assertThat(SourceMap.of(ClassFile.of().parse(mapped)).copied(55)).isEmpty();
  }
}
