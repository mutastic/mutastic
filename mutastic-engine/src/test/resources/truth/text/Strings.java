package text;

import java.util.Objects;

/// Each string's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Strings {
  static String returned(String a, String b) {
    return "x";
  }

  static String returnedClaim(String a, String b) {
    return "";
  }

  static boolean compared(String a, String b) {
    return a.equals("a");
  }

  static boolean comparedClaim(String a, String b) {
    return a.equals("");
  }

  static String appended(String a, String b) {
    return new StringBuilder(a).append("!").toString();
  }

  static String appendedClaim(String a, String b) {
    return new StringBuilder(a).append("").toString();
  }

  static String empty(String a, String b) {
    return "";
  }

  static String emptyClaim(String a, String b) {
    return "mutastic";
  }

  static String aFormat(String a, String b) {
    return "%s!".formatted(a);
  }

  static String aFormatClaim(String a, String b) {
    return "".formatted(a);
  }

  private static final String NAMED = "k";

  static String aStaticFinal(String a, String b) {
    return NAMED + "";
  }

  static String aStaticFinalClaim(String a, String b) {
    return "";
  }

  static String multiLine(String a, String b) {
    return """
        one
        two""";
  }

  static String multiLineClaim(String a, String b) {
    return "";
  }

  static String aLongOne(String a, String b) {
    return "a string of more words than a label should have to hold";
  }

  static String aLongOneClaim(String a, String b) {
    return "";
  }

  /// A builder whose text goes somewhere else is no message.
  static String builtAndReturned(String a, String b) {
    return new StringBuilder().append("kept ").append(a).toString();
  }

  static String builtAndReturnedClaim(String a, String b) {
    return new StringBuilder().append("").append(a).toString();
  }

  /// Named as errors are, and not one: its text is a value something reads.
  static final class ConfigError {
    final String key;

    ConfigError(String key) {
      this.key = key;
    }
  }

  static String namedAsAnError(String a, String b) {
    return new ConfigError("key").key;
  }

  static String namedAsAnErrorClaim(String a, String b) {
    return new ConfigError("").key;
  }

  /// A helper's text, on its way to an exception the walk can't see from here.
  static ParseFailure failure(String message) {
    return new ParseFailure(message);
  }

  static String messageForAHelper(String a, String b) {
    return failure("no such option").getMessage();
  }

  static String messageForAHelperClaim(String a, String b) {
    return failure("").getMessage();
  }

  static String notTheChecksOfALibrary(String a, String b) {
    return Preconditions.named("key");
  }

  static String notTheChecksOfALibraryClaim(String a, String b) {
    return Preconditions.named("");
  }

  // What's skipped, or has no change.

  /// A project's own exceptions, whose names don't say so.
  static class ParseFailure extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ParseFailure(String message) {
      super(message);
    }
  }

  static final class NoSuchOption extends ParseFailure {
    private static final long serialVersionUID = 1L;

    NoSuchOption(String message) {
      super(message);
    }
  }

  /// Built by a helper and thrown by its caller, so it isn't thrown straight away here.
  static ParseFailure builtForItsCaller(String a, String b) {
    return new ParseFailure("no match");
  }

  static NoSuchOption aSubclassOfOurOwn(String a, String b) {
    return new NoSuchOption("no such");
  }

  static void logged(String a, String b) {
    System.out.println("seen");
  }

  static void thrown(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException("the same");
    }
  }

  static IllegalArgumentException built(String a, String b) {
    return new IllegalArgumentException("built");
  }

  static void formattedIntoAnException(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException("%s and %s".formatted(a, b));
    }
  }

  static void formatIntoAnException(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException(String.format("%s and %s", a, b));
    }
  }

  /// As javac writes `"text " + a` for a class older than Java 9.
  static void builtIntoAnException(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException(new StringBuilder().append("in full ").append(a).toString());
    }
  }

  static void builderStartedIntoAnException(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException(new StringBuilder("to start ").append(a).toString());
    }
  }

  static String checked(String a, String b) {
    return Objects.requireNonNull(a, "a is needed");
  }

  static String concatenated(String a, String b) {
    return "text " + a;
  }

  @SuppressWarnings("unused")
  static int annotated(String a, String b) {
    return 1;
  }

  static int aSwitch(String a, String b) {
    switch (a) {
      case "a":
        return 1;
      default:
        return 0;
    }
  }
}

/// A project's own class of this name, not a library's: a string it's given may be anything.
final class Preconditions {
  private Preconditions() {}

  static String named(String key) {
    return key;
  }
}
