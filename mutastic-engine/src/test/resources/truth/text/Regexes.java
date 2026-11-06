package text;

import java.util.regex.Pattern;

/// Each expression's change, written out: `xClaim` takes the expression the mutant's label ends in.
class Regexes {
  private static final Pattern IN_A_FIELD = Pattern.compile("^b+");

  private final Pattern perInstance = Pattern.compile("^a");

  static String anchoredAtTheStart(String a, String b) {
    return Pattern.compile("^a+").matcher(a).replaceAll("x");
  }

  static String anchoredAtTheStartClaim(String a, String b, String regex) {
    return Pattern.compile(regex).matcher(a).replaceAll("x");
  }

  static String anchoredAtTheEnd(String a, String b) {
    return Pattern.compile("a+$").matcher(a).replaceAll("x");
  }

  static String anchoredAtTheEndClaim(String a, String b, String regex) {
    return Pattern.compile(regex).matcher(a).replaceAll("x");
  }

  static String aClass(String a, String b) {
    return a.replaceAll("[ab]", "x");
  }

  static String aClassClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aNegatedClass(String a, String b) {
    return a.replaceAll("[^ab]", "x");
  }

  static String aNegatedClassClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aDigit(String a, String b) {
    return a.replaceFirst("\\d", "x");
  }

  static String aDigitClaim(String a, String b, String regex) {
    return a.replaceFirst(regex, "x");
  }

  static String aSpace(String a, String b) {
    return a.replaceAll("\\s", "x");
  }

  static String aSpaceClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aStar(String a, String b) {
    return a.replaceAll("ba*", "x");
  }

  static String aStarClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aLazyPlus(String a, String b) {
    return a.replaceAll("a*?b", "x");
  }

  static String aLazyPlusClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aCount(String a, String b) {
    return a.replaceAll("a{2}", "x");
  }

  static String aCountClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String aGroup(String a, String b) {
    return a.replaceAll("(?:a)*b", "x");
  }

  static String aGroupClaim(String a, String b, String regex) {
    return a.replaceAll(regex, "x");
  }

  static String split(String a, String b) {
    return String.join("|", a.split("a?b"));
  }

  static String splitClaim(String a, String b, String regex) {
    return String.join("|", a.split(regex));
  }

  static String splitWithALimit(String a, String b) {
    return String.join("|", a.split("a?b", 2));
  }

  static String splitWithALimitClaim(String a, String b, String regex) {
    return String.join("|", a.split(regex, 2));
  }

  static boolean wholeText(String a, String b) {
    return a.matches("a+");
  }

  static boolean wholeTextClaim(String a, String b, String regex) {
    return a.matches(regex);
  }

  static boolean wholeTextByPattern(String a, String b) {
    return Pattern.matches("[ab]", a);
  }

  static boolean wholeTextByPatternClaim(String a, String b, String regex) {
    return Pattern.matches(regex, a);
  }

  static String withFlags(String a, String b) {
    return Pattern.compile("^a", Pattern.CASE_INSENSITIVE).matcher(a).replaceAll("x");
  }

  static String withFlagsClaim(String a, String b, String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(a).replaceAll("x");
  }

  // Skipped, or with no change.

  /// `matches` has to match all of it, so its anchors are skipped, and its `+` isn't.
  static boolean anchorsThatDoNothing(String a, String b) {
    return a.matches("^a+$");
  }

  static boolean anchorsThatDoNothingClaim(String a, String b, String regex) {
    return a.matches(regex);
  }

  static String theReplacement(String a, String b) {
    return a.replaceAll("b", "$0$0");
  }

  static boolean asTheReceiver(String a, String b) {
    return "a+".equals(b);
  }

  static boolean notARegex(String a, String b) {
    return a.contains("a+");
  }

  static boolean throughAVariable(String a, String b) {
    String regex = "a+";
    return a.matches(regex);
  }

  static String plainText(String a, String b) {
    return String.join("|", a.split(","));
  }

  // On a static or instance field: a real run's class load, and a real constructor.

  static String inAField(String a, String b) {
    return IN_A_FIELD.matcher(a).replaceAll("x");
  }

  String perInstance(String a, String b) {
    return perInstance.matcher(a).replaceAll("x");
  }
}
