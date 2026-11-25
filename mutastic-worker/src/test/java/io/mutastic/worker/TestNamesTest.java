package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.MethodSource;

/// Every form a run prints a test in leaves that test out, as JUnit names it: a class by its name
/// or full name, a method with every overload, and one overload by its parameter types, full or by
/// name alone. A name a run doesn't print leaves out nothing.
class TestNamesTest {
  /// Overloads, as a test class might have them: none, an array and a nested type.
  @SuppressWarnings("unused")
  static final class Shapes {
    void cart() {}

    void cart(String[] lines, int count, Map.Entry<String, Integer> first) {}
  }

  private static final String CLASS = Shapes.class.getName();

  /// The test as the worker names it, from JUnit's own source for the method.
  private static String id(Class<?>... parameters) throws NoSuchMethodException {
    var source = MethodSource.from(Shapes.class.getDeclaredMethod("cart", parameters));
    return TestNames.id(
        source.getClassName(), source.getMethodName(), source.getMethodParameterTypes());
  }

  @Test
  void theFormsARunPrintsEachLeaveTheTestOut() throws Exception {
    var withTypes = id(String[].class, int.class, Map.Entry.class);
    var shortName = withTypes.substring(withTypes.lastIndexOf('.', withTypes.indexOf('#')) + 1);

    assertThat(withTypes).startsWith(CLASS + "#cart(");
    assertThat(
            List.of(
                withTypes,
                shortName,
                CLASS,
                "TestNamesTest$Shapes",
                CLASS + "#cart",
                "TestNamesTest$Shapes#cart",
                "TestNamesTest$Shapes#cart(String[], int, Map$Entry)",
                "TestNamesTest$Shapes#cart(java.lang.String[],int,java.util.Map$Entry)",
                "TestNamesTest$Shapes#cart(String..., int, Map$Entry)",
                " TestNamesTest$Shapes # cart ( String[] , int, Map$Entry ) "))
        .allSatisfy(name -> assertThat(TestNames.leavesOut(name, withTypes)).as(name).isTrue());
  }

  @Test
  void anOverloadByItsTypesLeavesOutOnlyThatOne() throws Exception {
    var none = id();
    var three = id(String[].class, int.class, Map.Entry.class);

    assertThat(TestNames.leavesOut("TestNamesTest$Shapes#cart()", none)).isTrue();
    assertThat(TestNames.leavesOut("TestNamesTest$Shapes#cart()", three)).isFalse();
    assertThat(TestNames.leavesOut("TestNamesTest$Shapes#cart(String[], int, Map$Entry)", none))
        .isFalse();
    assertThat(TestNames.leavesOut("TestNamesTest$Shapes#cart", none)).isTrue();
  }

  @Test
  void aNameARunDoesntPrintLeavesOutNothing() throws Exception {
    var three = id(String[].class, int.class, Map.Entry.class);

    assertThat(
            List.of(
                "Shapes#cart",
                "TestNamesTest$Shapes#carts",
                "TestNamesTest$Shapes#cart(String, int, Map$Entry)",
                "TestNamesTest$Shapes#cart(String[], int)",
                "other.TestNamesTest$Shapes#cart",
                "TestNamesTest$Shape"))
        .allSatisfy(name -> assertThat(TestNames.leavesOut(name, three)).as(name).isFalse());
  }

  @Test
  void aClassIsLeftOutWholeOnlyByAClassName() {
    assertThat(TestNames.leavesOutClass("PricingTest", "com.example.PricingTest")).isTrue();
    assertThat(TestNames.leavesOutClass("com.example.PricingTest", "com.example.PricingTest"))
        .isTrue();
    assertThat(TestNames.leavesOutClass("PricingTest#bigOrders", "com.example.PricingTest"))
        .isFalse();
    assertThat(TestNames.leavesOutClass("Pricing", "com.example.PricingTest")).isFalse();
    assertThat(TestNames.leavesOut("PricingTest", "com.example.PricingTest")).isTrue();
  }

  @Test
  void namesSplitAtCommasOutsideTheirParameterTypes() {
    assertThat(
            TestNames.split(" RacyTest, PricingTest#bigOrders(Cart, int),,com.example.PortTest "))
        .containsExactly("RacyTest", "PricingTest#bigOrders(Cart, int)", "com.example.PortTest");
    assertThat(TestNames.split("")).isEmpty();
  }
}
