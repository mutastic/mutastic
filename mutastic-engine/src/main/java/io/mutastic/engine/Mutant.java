package io.mutastic.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/// One mutation of one instruction: which method, which mutation site in it (counted in bytecode
/// order), and what the instruction becomes. A skipped mutant never runs: the change can't be
/// told apart from the original, or it's code no test is meant to pin.
///
/// @param className the class's internal name, `com/example/Pricing`
/// @param ordinal which site in the method, counted per kind of site: every arithmetic, branch
///     and return instruction counts as an instruction site, and every call as a call site,
///     whichever operators apply, so a new operator never shifts the ids of existing mutants
/// @param original the instruction: an opcode, `IF_ICMPLT`, or a call,
///     `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`
/// @param replacement what it becomes: `IF_ICMPLE`, `return default` or `remove call`
/// @param skipped why it doesn't run, `equivalent: already returns the default`, if it doesn't
public record Mutant(
    String className,
    String methodName,
    String descriptor,
    int ordinal,
    int line,
    Operator operator,
    String original,
    String replacement,
    Optional<String> skipped) {

  /// Stable across runs and line moves: class, method, site and replacement, but no line
  /// number, because code moves during review far more often than it changes.
  public String fingerprint() {
    var key = className + "#" + methodName + descriptor + "@" + ordinal + ":" + replacement;
    try {
      var hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }

  /// `m11989fc0`: what people type.
  public String id() {
    return "m" + fingerprint().substring(0, 8);
  }

  /// `com.example.Pricing`.
  public String binaryName() {
    return className.replace('/', '.');
  }
}
