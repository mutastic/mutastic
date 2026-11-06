package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceNamesTest {
  @Test
  void javacsNamesAsTheSourceHasThem() {
    assertThat(where("com/acme/Shipping$Zone", "surcharge"))
        .isEqualTo(new SourceNames.Where("Shipping.Zone", "surcharge", Optional.empty()));
    assertThat(where("com/acme/Shipping$1", "compare"))
        .isEqualTo(new SourceNames.Where("Shipping", "compare", Optional.of("an anonymous class")));
    assertThat(where("com/acme/Shipping$1Sum", "of"))
        .isEqualTo(new SourceNames.Where("Shipping", "of", Optional.of("the local class `Sum`")));
    assertThat(where("com/acme/Shipping$Speed$1", "days"))
        .isEqualTo(
            new SourceNames.Where("Shipping.Speed", "days", Optional.of("an anonymous class")));
    assertThat(where("com/acme/Shipping", "lambda$heavyOnes$0"))
        .isEqualTo(new SourceNames.Where("Shipping", "heavyOnes", Optional.of("a lambda")));
    assertThat(SourceNames.sourceName("ShippingTest$Zones")).isEqualTo("ShippingTest.Zones");
  }

  private static SourceNames.Where where(String className, String method) {
    return SourceNames.of(
        new Mutant(
            className,
            method,
            "()I",
            0,
            1,
            BuiltIn.RETURN_VALUE,
            "IRETURN",
            "return 0",
            Optional.empty()));
  }
}
