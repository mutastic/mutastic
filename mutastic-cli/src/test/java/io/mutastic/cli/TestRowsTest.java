package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests counted as JUnit runs them: each row of a static source one, from the annotations; what
/// code makes counted as the method, for a run to count; comments not at all.
class TestRowsTest {
  @TempDir Path work;

  @Test
  void eachRowOfAStaticSourceIsATest() {
    var rows =
        TestRows.of(
            """
            class CalculatorTest {
              @Test void plain() {}

              @RepeatedTest(3) void again() {}

              @ParameterizedTest(name = "{0} + {1} = {2} \\"quoted\\", #hash [x] (y)")
              @CsvSource({"1, 1, 2", "2, 3, 5"})
              void adds(int a, int b, int sum) {}

              @ParameterizedTest
              @CsvSource(value = {"1; 2"}, delimiter = ';')
              void named(int a, int b) {}

              @ParameterizedTest
              @CsvSource(textBlock = \"""
                  # a CSV comment, not a row
                  1, 1

                  2, 4
                  \""")
              void block(int a, int b) {}

              @ParameterizedTest
              @ValueSource(ints = {2, 4, 6})
              @NullSource
              void evens(Integer n) {}

              @ParameterizedTest
              @EnumSource(value = Day.class, names = {"MONDAY", "FRIDAY"})
              void days(Day day) {}
            }
            """,
            work);

    assertThat(rows).isEqualTo(new TestRows(1 + 3 + 2 + 1 + 2 + 4 + 2, 0, 0));
  }

  /// Rows code makes are one test until a run counts them; so is a test a condition may turn off.
  @Test
  void whatOnlyARunCountsIsOneTest() {
    var rows =
        TestRows.of(
            """
            class T {
              @ParameterizedTest @MethodSource("totals") void discounts(int t) {}
              @ParameterizedTest @ArgumentsSource(Rows.class) void rows(int t) {}
              @ParameterizedTest @EnumSource(Day.class) void days(Day d) {}
              @Test @DisabledOnOs(OS.WINDOWS) void notOnWindows() {}
              @TestFactory Stream<DynamicTest> made() { return Stream.empty(); }
            }
            """,
            work);

    assertThat(rows).isEqualTo(new TestRows(5, 0, 5));
  }

  /// `@Disabled` on a method, or on a class around it, turns all its rows off; so does JUnit 4's
  /// `@Ignore` and TestNG's `enabled = false`.
  @Test
  void disabledRowsAreCountedAsDisabled() {
    var rows =
        TestRows.of(
            """
            class T {
              @Disabled @ParameterizedTest @ValueSource(strings = {"a", "b"}) void off(String s) {}
              @Ignore @Test public void ignored() {}
              @Test(enabled = false) public void notEnabled() {}
              @Test void on() {}

              @Nested @Disabled class Later {
                @ParameterizedTest @CsvSource({"1", "2", "3"}) void rows(int n) {}
                @Nested class Deeper {
                  @Test void too() {}
                }
              }
            }
            """,
            work);

    assertThat(rows).isEqualTo(new TestRows(2 + 1 + 1 + 1 + 3 + 1, 2 + 1 + 1 + 3 + 1, 0));
  }

  /// Only code counts: a test, a row or a `@Disabled` in a comment or a string isn't one.
  @Test
  void commentsAndStringsAreLeftOut() {
    var rows =
        TestRows.of(
            """
            class T {
              // @Test void old() {}
              /* @Disabled
                 @Test void older() {} */
              @Test void real() { var s = "@Test @Disabled"; }
              @ParameterizedTest
              @CsvSource({
                "1, 1", // "2, 2",
                /* "3, 3", */ "4, 4"
              })
              void rows(int a, int b) {}
            }
            """,
            work);

    assertThat(rows).isEqualTo(new TestRows(3, 0, 0));
  }

  @Test
  void kotlinsAnnotationsCountTheSame() {
    var rows =
        TestRows.of(
            """
            class CalculatorTest {
                @ParameterizedTest(name = "{0} - {1} = {2}")
                @CsvSource("30, 0, 30", "1, 1, 0")
                fun `subtracts with spaces`(a: Int, b: Int, expected: Int) {}

                @ParameterizedTest
                @ValueSource(ints = [2, 4])
                fun evens(n: Int) {}

                @Disabled
                @ParameterizedTest
                @CsvSource(textBlock = \"""
                    1, 1
                    2, 2
                \""")
                fun off(a: Int, b: Int) {}

                @ParameterizedTest
                @MethodSource("totals")
                fun discounts(total: Int, expected: Int) {}

                companion object {
                    @JvmStatic
                    fun totals(): Stream<Arguments> = Stream.of(Arguments.of(50, 0))
                }
            }
            """,
            work);

    assertThat(rows).isEqualTo(new TestRows(2 + 2 + 2 + 1, 2, 1));
  }

  /// A `@CsvFileSource`'s rows are its file's lines, less those it skips.
  @Test
  void aCsvFilesRowsAreItsLines() throws Exception {
    var root = Files.createDirectories(work.resolve("src/test/java"));
    Files.createDirectories(work.resolve("src/test/resources"));
    Files.writeString(work.resolve("src/test/resources/rows.csv"), "a,b\n1,2\n# not a row\n3,4\n");

    var rows =
        TestRows.of(
            """
            class T {
              @ParameterizedTest
              @CsvFileSource(resources = "/rows.csv", numLinesToSkip = 1)
              void rows(int a, int b) {}

              @ParameterizedTest
              @CsvFileSource(resources = "/missing.csv")
              void missing(int a) {}
            }
            """,
            root);

    assertThat(rows).isEqualTo(new TestRows(2 + 1, 0, 1));
  }
}
