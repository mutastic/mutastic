package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import demo.args.Holder;
import org.junit.jupiter.api.Test;

class BinderTest {
  static final class Nested {}

  @Test
  void twice() {
    assertEquals(6, Binder.twice(3));
  }

  // Holder, a test class in another package, has a field of the test class Point (jcommander).
  @Test
  void readsAHolderInAnotherPackage() {
    assertEquals(7, new Holder().point.x);
  }

  // A test that sees its own class's name (kotlinpoet's generated code names its test classes).
  @Test
  void namesItsNestedClass() {
    assertEquals("demo.BinderTest$Nested", Nested.class.getName());
  }
}
