package io.mutastic.worker;

import java.util.regex.Pattern;
import org.junit.platform.launcher.TestIdentifier;

/// The case a failing test is, when it's one of a table its method runs: an invocation of a
/// parameterized or repeated test (`[test-template-invocation:#2]` ends its unique id) or a dynamic
/// test (`[dynamic-test:#2]`). The protocol carries it as its index from 1 and its display name,
/// `2\t[2] 2, 3, 5`, and `-\t-` for a test that's no case: the engine names the method either way,
/// and the case only as evidence of this run.
final class FailedCase {
  /// What the protocol says for a failure that's no case.
  static final String NONE = "-\t-";

  /// The most of a display name the protocol carries: a name can hold a whole argument.
  private static final int NAME = 200;

  private static final Pattern LAST =
      Pattern.compile("\\[(?<type>test-template-invocation|dynamic-test):#(?<index>\\d+)\\]$");

  private FailedCase() {}

  /// `2\t[2] 2, 3, 5`, or [#NONE].
  static String of(TestIdentifier test) {
    var last = LAST.matcher(test.getUniqueId());
    if (!test.isTest() || !last.find()) {
      return NONE;
    }
    return last.group("index") + "\t" + named(test.getDisplayName());
  }

  /// On one line, as the protocol needs, and no longer than it carries.
  private static String named(String displayName) {
    var line = displayName.replaceAll("\\s", " ").strip();
    return line.isEmpty() ? "-" : line.substring(0, Math.min(line.length(), NAME));
  }
}
