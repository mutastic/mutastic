package calls;

import java.util.Objects;
import java.util.regex.Pattern;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class LessArguments {
  static int indexFrom(String a, String b) {
    return a.indexOf(b, 1);
  }

  static int indexFromClaim(String a, String b) {
    return a.indexOf(b);
  }

  static int lastIndexOfACharacter(String a, String b) {
    return a.lastIndexOf(b.charAt(0), 1);
  }

  static int lastIndexOfACharacterClaim(String a, String b) {
    return a.lastIndexOf(b.charAt(0));
  }

  static String substringBetween(String a, String b) {
    return a.substring(0, Math.min(1, a.length())) + b;
  }

  static String substringBetweenClaim(String a, String b) {
    return a.substring(0) + b;
  }

  static String aBuildersSubstring(String a, String b) {
    return new StringBuilder(a).substring(0, Math.min(1, a.length())) + b;
  }

  static String aBuildersSubstringClaim(String a, String b) {
    return new StringBuilder(a).substring(0) + b;
  }

  static String radix(String a, String b) {
    return Integer.toString(a.length() + 10, 16) + b;
  }

  static String radixClaim(String a, String b) {
    return Integer.toString(a.length() + 10) + b;
  }

  static String aWideRadix(String a, String b) {
    return Long.toString(a.length() + 10L, 16) + b;
  }

  static String aWideRadixClaim(String a, String b) {
    return Long.toString(a.length() + 10L) + b;
  }

  static int parsed(String a, String b) {
    return Integer.parseInt(a.isEmpty() ? "11" : "1" + a.length(), 16) + b.length();
  }

  static int parsedClaim(String a, String b) {
    return Integer.parseInt(a.isEmpty() ? "11" : "1" + a.length()) + b.length();
  }

  static long parsedWide(String a, String b) {
    return Long.parseLong("1" + a.length(), 16) + b.length();
  }

  static long parsedWideClaim(String a, String b) {
    return Long.parseLong("1" + a.length()) + b.length();
  }

  static boolean compiledWithFlags(String a, String b) {
    return Pattern.compile("A", Pattern.CASE_INSENSITIVE).matcher(a).find() && b != null;
  }

  static boolean compiledWithFlagsClaim(String a, String b) {
    return Pattern.compile("A").matcher(a).find() && b != null;
  }

  static String defaulted(String a, String b) {
    return Objects.toString(a.equals("a") ? null : a, b);
  }

  static String defaultedClaim(String a, String b) {
    return Objects.toString(a.equals("a") ? null : a);
  }

  // Left alone: no overload without it, or one its result wouldn't show.

  static int threeArguments(String a, String b) {
    return a.indexOf(b.charAt(0), 0, a.length());
  }

  static String theOneArgumentSubstring(String a, String b) {
    return a.substring(0) + b;
  }

  static String theOneArgumentToString(String a, String b) {
    return Integer.toString(a.length()) + b;
  }

  static String[] aSplitLimit(String a, String b) {
    return a.split(",", 2);
  }

  static String aLocale(String a, String b) {
    return a.toLowerCase(java.util.Locale.ROOT) + b;
  }
}
