package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/// A suggested call names a class as its source declares it, or isn't offered: a `$` in a binary
/// name doesn't always mean nesting.
class JavaNamesTest {
  private static final String CACHE =
      """
      package names;
      public final class $Cache {
        public static final class Entry {
          public static final class Key {}
        }
        Runnable task() { return new Runnable() { public void run() {} }; }
      }
      """;

  /// What a framework writes for `$Cache`: a top-level class whose name holds a second `$`.
  private static final String DEFINITION =
      """
      package names;
      public final class $Cache$Definition {}
      """;

  private static Map<String, ClassModel> models() throws Exception {
    var compiled =
        Javac.compile(
            List.of(
                new Javac.Source("names.$Cache", CACHE),
                new Javac.Source("names.$Cache$Definition", DEFINITION)),
            List.of());
    var models = new TreeMap<String, ClassModel>();
    compiled.forEach((name, bytes) -> models.put(name, ClassFile.of().parse(bytes)));
    return models;
  }

  @Test
  void aDollarDividesOnlyBetweenTwoOtherCharacters() {
    assertThat(JavaNames.nested("Shipping$Parcel")).isEqualTo("Shipping.Parcel");
    assertThat(JavaNames.nested("$Cache")).isEqualTo("$Cache");
    assertThat(JavaNames.nested("$Cache$Entry")).isEqualTo("$Cache.Entry");
    assertThat(JavaNames.nested("Total$")).isEqualTo("Total$");
    assertThat(JavaNames.nested("Pricing$$Rule")).isEqualTo("Pricing$$Rule");
  }

  @Test
  void theNameTheSourceDeclares() throws Exception {
    var models = models();

    assertThat(JavaNames.declared(models.get("names.$Cache"))).contains("$Cache");
    assertThat(JavaNames.declared(models.get("names.$Cache$Entry"))).contains("$Cache.Entry");
    assertThat(JavaNames.declared(models.get("names.$Cache$Entry$Key")))
        .contains("$Cache.Entry.Key");
    assertThat(JavaNames.declared(models.get("names.$Cache$Definition")))
        .contains("$Cache$Definition");
    assertThat(JavaNames.declared(models.get("names.$Cache$1"))).isEmpty();
  }

  @Test
  void aCallIsOfferedOnlyWhereItsNamesAreTheDeclaredOnes() throws Exception {
    var models = models();

    assertThat(JavaNames.writtenAsDeclared(List.of("names/$Cache", "names/$Cache$Entry"), models))
        .isTrue();
    assertThat(JavaNames.writtenAsDeclared(List.of("names/$Cache$Definition"), models)).isFalse();
    assertThat(JavaNames.writtenAsDeclared(List.of("names/$Cache$1"), models)).isFalse();
    assertThat(JavaNames.writtenAsDeclared(List.of("elsewhere/Pricing"), models)).isTrue();
  }
}
