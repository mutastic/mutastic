package calls;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Dropped {
  private static String joined(String... parts) {
    return String.join("|", parts) + parts.length;
  }

  private static int sum(int... values) {
    return Arrays.stream(values).sum() + 100 * values.length;
  }

  private static long sumOfLongs(long... values) {
    return Arrays.stream(values).sum() + 100 * values.length;
  }

  private static String named(String name, Object... more) {
    return name + Arrays.toString(more);
  }

  private static int firstLength(String[][] groups) {
    return groups.length;
  }

  private static String kept(String[] values) {
    return String.join(",", values) + values.length;
  }

  static String strings(String a, String b) {
    return joined(a, b);
  }

  static String stringsClaim(String a, String b) {
    return joined(a);
  }

  static String theOnlyOne(String a, String b) {
    return joined(a) + b;
  }

  static String theOnlyOneClaim(String a, String b) {
    return joined() + b;
  }

  static int ints(String a, String b) {
    return sum(a.length(), b.length(), 7);
  }

  static int intsClaim(String a, String b) {
    return sum(a.length(), b.length());
  }

  static long longs(String a, String b) {
    return sumOfLongs(a.length(), b.length());
  }

  static long longsClaim(String a, String b) {
    return sumOfLongs(a.length());
  }

  static String afterAnotherArgument(String a, String b) {
    return named("n", a, b.length());
  }

  static String afterAnotherArgumentClaim(String a, String b) {
    return named("n", a);
  }

  static String aLiteral(String a, String b) {
    return kept(new String[] {a, b});
  }

  static String aLiteralClaim(String a, String b) {
    return kept(new String[] {a});
  }

  static int anArrayOfArrays(String a, String b) {
    return firstLength(new String[][] {{a}, {b}});
  }

  static int anArrayOfArraysClaim(String a, String b) {
    return firstLength(new String[][] {{a}});
  }

  static int aLibrarys(String a, String b) {
    return List.of(a, b, a, b, a, b, a, b, a, b, a).size();
  }

  static int aLibrarysClaim(String a, String b) {
    return List.of(a, b, a, b, a, b, a, b, a, b).size();
  }

  static String inALambda(String a, String b) {
    Supplier<String> parts = () -> joined(a, b);
    return parts.get();
  }

  static String inALambdaClaim(String a, String b) {
    return joined(a);
  }

  // The last value's still worked out: a call in it still runs.
  static String theLastValueStillRuns(String a, String b) {
    var log = new StringBuilder();
    return joined(a, log.append(b).toString()) + log;
  }

  static String theLastValueStillRunsClaim(String a, String b) {
    var log = new StringBuilder();
    log.append(b);
    return joined(a) + log;
  }

  // Left alone: the array wasn't built for the call, or may be empty.

  static String noneGiven(String a, String b) {
    return joined() + a + b;
  }

  static String anExistingArray(String a, String b) {
    String[] parts = {a, b};
    return joined(parts);
  }

  static String allocatedOnly(String a, String b) {
    return kept(new String[a.length()]) + b;
  }

  static String theValueOfAnAssignment(String a, String b) {
    String[][] holder = new String[1][];
    return kept(holder[0] = new String[0]) + a + b;
  }
}
