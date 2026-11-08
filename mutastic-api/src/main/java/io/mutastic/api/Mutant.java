package io.mutastic.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/// One mutation of one instruction: which method, which mutation site in it (counted in bytecode
/// order), and what the instruction becomes. A skipped mutant never runs: the change can't be told
/// apart from the original, or it's code no test is meant to pin.
///
/// @param className the class's internal name, `com/example/Pricing`
/// @param ordinal which site in the method, counted per kind of site: every arithmetic, branch and
///   return instruction counts as an instruction site, and every call as a call site, whichever
///   operators apply, so a new operator never shifts the ids of existing mutants
/// @param original the instruction: an opcode, `IF_ICMPLT`, or a call, `INVOKEVIRTUAL
///   com/example/Audit.record(Ljava/lang/String;)V`
/// @param replacement what it becomes: `IF_ICMPLE`, `return false` or `remove call`
/// @param skipped why it doesn't run, `equivalent: already returns the default`, if it doesn't
/// @param copies more sites (of the same kind) that get the same change at the same time: the
///   copies javac makes of a `finally` block, one per way out of the `try`
/// @param operands what the instruction works on, as the source names it (`total`, `100`), when the
///   bytecode before it says; not part of the fingerprint
/// @param inlinedFrom the source file `line` is in, under a source root (`probe/Helpers.kt`), when
///   the instruction is a copy of code from another file than the class's: what kotlinc's inline
///   functions leave; not part of the fingerprint
public record Mutant(
    String className,
    String methodName,
    String descriptor,
    int ordinal,
    int line,
    Operator operator,
    String original,
    String replacement,
    Optional<String> skipped,
    List<Integer> copies,
    List<String> operands,
    Optional<String> inlinedFrom) {

  public Mutant {
    copies = List.copyOf(copies);
    operands = List.copyOf(operands);
  }

  /// A mutant in its class's own file.
  public Mutant(
      String className,
      String methodName,
      String descriptor,
      int ordinal,
      int line,
      Operator operator,
      String original,
      String replacement,
      Optional<String> skipped,
      List<Integer> copies,
      List<String> operands) {
    this(
        className,
        methodName,
        descriptor,
        ordinal,
        line,
        operator,
        original,
        replacement,
        skipped,
        copies,
        operands,
        Optional.empty());
  }

  /// A mutant whose operands aren't known.
  public Mutant(
      String className,
      String methodName,
      String descriptor,
      int ordinal,
      int line,
      Operator operator,
      String original,
      String replacement,
      Optional<String> skipped,
      List<Integer> copies) {
    this(
        className,
        methodName,
        descriptor,
        ordinal,
        line,
        operator,
        original,
        replacement,
        skipped,
        copies,
        List.of());
  }

  /// A mutant of one site.
  public Mutant(
      String className,
      String methodName,
      String descriptor,
      int ordinal,
      int line,
      Operator operator,
      String original,
      String replacement,
      Optional<String> skipped) {
    this(
        className,
        methodName,
        descriptor,
        ordinal,
        line,
        operator,
        original,
        replacement,
        skipped,
        List.of());
  }

  /// The same mutant, skipped for `reason` (`user: …`).
  @Internal
  public Mutant skippedFor(String reason) {
    return new Mutant(
        className,
        methodName,
        descriptor,
        ordinal,
        line,
        operator,
        original,
        replacement,
        Optional.of(reason),
        copies,
        operands,
        inlinedFrom);
  }

  /// The same mutant, to run: for an audit of what a filter skips.
  @Internal
  public Mutant unskipped() {
    return new Mutant(
        className,
        methodName,
        descriptor,
        ordinal,
        line,
        operator,
        original,
        replacement,
        Optional.empty(),
        copies,
        operands,
        inlinedFrom);
  }

  /// The same mutant, at where its code really is: `file` when that's another file than its
  /// class's.
  @Internal
  public Mutant at(int realLine, Optional<String> file) {
    return new Mutant(
        className,
        methodName,
        descriptor,
        ordinal,
        realLine,
        operator,
        original,
        replacement,
        skipped,
        copies,
        operands,
        file);
  }

  /// Whether the change is made at `site` (counted in this mutant's kind of site).
  @Internal
  public boolean changes(int site) {
    return site == ordinal || copies.contains(site);
  }

  /// Stable across runs and line moves: class, method, site and replacement, and the operator for
  /// all but the first ones still built in ([BuiltIn#first]), but no line number, because code
  /// moves during review far more often than it changes. A `finally` block's copies share the first
  /// copy's fingerprint.
  public String fingerprint() {
    var key =
        className
            + "#"
            + methodName
            + descriptor
            + "@"
            + ordinal
            + ":"
            + replacement
            + (operator instanceof BuiltIn builtIn && builtIn.first() ? "" : "|" + operator.id());
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
