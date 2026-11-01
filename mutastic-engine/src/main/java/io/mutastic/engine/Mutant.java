package io.mutastic.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// One mutation of one instruction: which method, which mutation site in it (counted in bytecode
/// order), and what the instruction becomes.
///
/// @param className the class's internal name, `com/example/Pricing`
/// @param ordinal which mutation site in the method: every arithmetic, branch and return
///     instruction counts, whichever operators apply to it, so a new operator never shifts the
///     ids of existing mutants
/// @param original the instruction's opcode, `IF_ICMPLT`
/// @param replacement what it becomes, `IF_ICMPLE`
public record Mutant(
    String className,
    String methodName,
    String descriptor,
    int ordinal,
    int line,
    Operator operator,
    String original,
    String replacement) {

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
