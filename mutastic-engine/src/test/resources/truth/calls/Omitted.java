package calls;

import java.util.function.Supplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Omitted {
  private final String text;

  Omitted(String text) {
    this.text = text;
  }

  // The JDK's, keeping what it's called on.

  static String trimmed(String a, String b) {
    return a.trim() + b.length();
  }

  static String trimmedClaim(String a, String b) {
    return a + b.length();
  }

  static String lowered(String a, String b) {
    String lowered = a.toLowerCase();
    return lowered + b.length();
  }

  static String loweredClaim(String a, String b) {
    String lowered = a;
    return lowered + b.length();
  }

  static String withAWideArgument(String a, String b) {
    return new StringBuilder(a).append((long) b.length()).toString();
  }

  static String withAWideArgumentClaim(String a, String b) {
    long unused = (long) b.length();
    return new StringBuilder(a).toString();
  }

  static String withTwoArguments(String a, String b) {
    return a.replace('a', 'b') + b.length();
  }

  static String withTwoArgumentsClaim(String a, String b) {
    return a + b.length();
  }

  static String inALambda(String a, String b) {
    Supplier<String> stripped = () -> a.strip();
    return stripped.get() + b.length();
  }

  static String inALambdaClaim(String a, String b) {
    return a + b.length();
  }

  // The JDK's, keeping the argument.

  static int anAbsoluteInt(String a, String b) {
    return Math.abs(a.length() - b.length());
  }

  static int anAbsoluteIntClaim(String a, String b) {
    return a.length() - b.length();
  }

  static double anAbsoluteDouble(String a, String b) {
    return Math.abs(a.length() - b.length() / 2.0);
  }

  static double anAbsoluteDoubleClaim(String a, String b) {
    return a.length() - b.length() / 2.0;
  }

  // The project's own: static, instance, wide, and a step in a chain.

  private static String escaped(String text) {
    return text.isEmpty() ? "none" : "<" + text + ">";
  }

  private String quoted(String other) {
    return text + "'" + other;
  }

  private long doubled(long value) {
    return value * text.length() + 1;
  }

  private Omitted with(String more) {
    return new Omitted(text + more);
  }

  static String aStaticWrapper(String a, String b) {
    return escaped(a) + b.length();
  }

  static String aStaticWrapperClaim(String a, String b) {
    return a + b.length();
  }

  static String anInstanceWrapper(String a, String b) {
    return new Omitted(a).quoted(b);
  }

  static String anInstanceWrapperClaim(String a, String b) {
    return b;
  }

  static long aWideInstanceWrapper(String a, String b) {
    return new Omitted(a).doubled(b.length());
  }

  static long aWideInstanceWrapperClaim(String a, String b) {
    return b.length();
  }

  static String aBuilderStep(String a, String b) {
    return new Omitted(a).with(b).text;
  }

  static String aBuilderStepClaim(String a, String b) {
    return new Omitted(a).text;
  }

  // Left alone. `trim` here is thrown away: removing a discarded call says that already. The rest
  // aren't sure to keep the type later code expects, or have two values to keep.

  static void discarded(String a, String b) {
    a.trim();
    new StringBuilder(a).append(b);
  }

  static <T> T generic(T value) {
    return value;
  }

  static String callsAGenericMethod(String a, String b) {
    return generic(a) + b.length();
  }

  static <T extends CharSequence> T bounded(T value) {
    return value;
  }

  static String callsABoundedGenericMethod(String a, String b) {
    return bounded(a) + b.length();
  }

  private static String either(String one, String other) {
    return one.length() > other.length() ? one : other;
  }

  static String twoToChooseFrom(String a, String b) {
    return either(a, b);
  }

  static String aLibrarysMethod(String a, String b) {
    return String.valueOf(a) + java.util.Objects.toString(b);
  }

  static long aLong(String a, String b) {
    return Long.valueOf(a.length()) + b.length();
  }
}
