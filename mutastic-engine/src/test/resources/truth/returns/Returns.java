package returns;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// One method for each way javac compiles a return a card describes. Each return is a line of its
/// own, so a card's changed line can be written in its place.
@SuppressWarnings("unused")
final class Returns {
  private Returns() {}

  static boolean literalTrue(int a, int b) {
    return true;
  }

  static boolean literalFalse(int a, int b) {
    return false;
  }

  static boolean comparison(int a, int b) {
    return a > b;
  }

  static boolean both(int a, int b) {
    return a > 0 && b > 0;
  }

  static boolean called(int a, int b) {
    return Integer.valueOf(a).equals(b);
  }

  static boolean stored(int a, int b) {
    boolean same = a == b;
    return same;
  }

  static boolean early(int a, int b) {
    if (a < 0) {
      return false;
    }
    return b > a;
  }

  static Boolean boxedTrue(int a, int b) {
    return true;
  }

  static Boolean boxedConstant(int a, int b) {
    return Boolean.FALSE;
  }

  static Boolean boxedComputed(int a, int b) {
    return a < b;
  }

  static int zero(int a, int b) {
    return 0;
  }

  static int sum(int a, int b) {
    return a + b;
  }

  static long longZero(int a, int b) {
    return 0L;
  }

  static long product(int a, int b) {
    return (long) a * b;
  }

  static double half(int a, int b) {
    return a / 2.0;
  }

  static double doubleZero(int a, int b) {
    return 0.0;
  }

  static float scaled(int a, int b) {
    return a * 0.5f;
  }

  static char letter(int a, int b) {
    return (char) ('e' + a);
  }

  static short narrow(int a, int b) {
    return (short) (a * b);
  }

  static Integer boxedZero(int a, int b) {
    return 0;
  }

  static Integer boxedSum(int a, int b) {
    return a + b;
  }

  static Long boxedLong(int a, int b) {
    return (long) a - b;
  }

  static Double boxedDouble(int a, int b) {
    return a * 1.5;
  }

  /// `values[0]` ends `iconst_0; aaload`: the constant is the index, not what's returned.
  static Integer firstOf(int a, int b) {
    Integer[] values = {a, b};
    return values[0];
  }

  static Boolean flagOf(int a, int b) {
    Boolean[] flags = {a > b};
    return flags[0];
  }

  static Character character(int a, int b) {
    return (char) ('e' + b);
  }

  static String text(int a, int b) {
    return "a" + a;
  }

  static String emptyText(int a, int b) {
    return "";
  }

  static String nullText(int a, int b) {
    return null;
  }

  static Optional<Integer> positive(int a, int b) {
    return a > 0 ? Optional.of(a) : Optional.empty();
  }

  static Optional<Integer> none(int a, int b) {
    return Optional.empty();
  }

  static List<Integer> pair(int a, int b) {
    return List.of(a, b);
  }

  static List<Integer> noList(int a, int b) {
    return List.of();
  }

  static List<Integer> emptyList(int a, int b) {
    return Collections.emptyList();
  }

  static Set<Integer> single(int a, int b) {
    return Set.of(a);
  }

  static Set<Integer> emptySet(int a, int b) {
    return Collections.emptySet();
  }

  static Map<Integer, Integer> entry(int a, int b) {
    return Map.of(a, b);
  }

  static Map<Integer, Integer> noMap(int a, int b) {
    return Map.of();
  }

  static Collection<Integer> collection(int a, int b) {
    return List.of(a);
  }

  static Iterable<Integer> iterable(int a, int b) {
    return List.of(a, b);
  }

  static Stream<Integer> stream(int a, int b) {
    return Stream.of(a, b);
  }

  static Stream<Integer> noStream(int a, int b) {
    return Stream.empty();
  }

  static Object object(int a, int b) {
    return a;
  }

  static Object nothing(int a, int b) {
    return null;
  }

  static int[] array(int a, int b) {
    return new int[] {a, b};
  }
}
