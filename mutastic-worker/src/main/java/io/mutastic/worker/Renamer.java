package io.mutastic.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.regex.Pattern;

/// Fresh copies of test classes that share a package with production code. A runtime package is a
/// package name *and* the loader that defined it, so a test in a child loader can't call its
/// production class's package-private test seam (`GameStore(rules, timeout, clock)`):
/// `IllegalAccessError`. Defining the test in the production loader fixes access, but a loader
/// defines a name once, so its statics would live across runs (a port picked once in a static). So
/// each run defines a renamed copy, `GameStoreTest__m7`, in the production loader: package access,
/// and statics that start clean.
///
/// The worker runs on the project's JDK (17+), so no ClassFile API: a class's names all live in its
/// constant pool's UTF-8 entries (class refs, descriptors, signatures, annotations), and renaming
/// is rewriting those.
final class Renamer {
  private static final Pattern SUFFIX = Pattern.compile("__m\\d+");

  private Renamer() {}

  /// `com.example.FooTest$Nested` in run 7 → `com.example.FooTest__m7$Nested`.
  static String renamed(String name, String topLevel, int run) {
    return topLevel + "__m" + run + name.substring(topLevel.length());
  }

  /// The name the build gave it: what tests are reported under.
  static String original(String name) {
    return SUFFIX.matcher(name).replaceAll("");
  }

  /// The run a renamed name belongs to, or -1 if it isn't one.
  static int runOf(String name) {
    var matcher = SUFFIX.matcher(name);
    return matcher.find()
        ? Integer.parseInt(name.substring(matcher.start() + 3, matcher.end()))
        : -1;
  }

  /// Every reference to one of `topLevels` (internal names, `com/example/FooTest`), or to a class
  /// nested in one, gets the run's suffix.
  static byte[] rename(byte[] bytes, Set<String> topLevels, int run) {
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      var buffer = new ByteArrayOutputStream(bytes.length + 256);
      var out = new DataOutputStream(buffer);
      out.writeInt(in.readInt()); // magic
      out.writeInt(in.readInt()); // minor, major
      int count = in.readUnsignedShort();
      out.writeShort(count);
      String suffix = "__m" + run;
      int index = 1;
      while (index < count) {
        index += copyConstant(in, out, topLevels, suffix);
      }
      in.transferTo(out); // the rest refers to the pool by index
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Copies one constant pool entry, renaming it if it's text; returns the slots it takes.
  private static int copyConstant(
      DataInputStream in, DataOutputStream out, Set<String> topLevels, String suffix)
      throws IOException {
    int tag = in.readUnsignedByte();
    out.writeByte(tag);
    switch (tag) {
      case 1 -> out.writeUTF(renameIn(in.readUTF(), topLevels, suffix));
      case 7, 8, 16, 19, 20 -> out.writeShort(in.readUnsignedShort());
      case 15 -> {
        out.writeByte(in.readUnsignedByte());
        out.writeShort(in.readUnsignedShort());
      }
      case 3, 4, 9, 10, 11, 12, 17, 18 -> out.writeInt(in.readInt());
      case 5, 6 -> {
        out.writeLong(in.readLong());
        return 2; // longs and doubles take two slots
      }
      default -> throw new IllegalArgumentException("constant pool tag " + tag);
    }
    return 1;
  }

  /// Inserts `suffix` after each whole top-level name: followed by the end, `;` (a descriptor), `<`
  /// (a signature) or `$` (a nested class), and not part of a longer name.
  private static String renameIn(String text, Set<String> topLevels, String suffix) {
    if (text.indexOf('/') < 0) {
      return text;
    }
    var result = text;
    for (String name : topLevels) {
      int from = 0;
      for (int at = result.indexOf(name, from); at >= 0; at = result.indexOf(name, from)) {
        int end = at + name.length();
        // `L` opens a descriptor's class (`(ILcom/example/FooTest;)V`); a package named
        // `Lcom` is the only way that misfires.
        char before = at == 0 ? ' ' : result.charAt(at - 1);
        boolean starts =
            before == 'L' || (!Character.isJavaIdentifierPart(before) && before != '/');
        boolean ends = end == result.length() || ";<$".indexOf(result.charAt(end)) >= 0;
        if (starts && ends) {
          result = result.substring(0, end) + suffix + result.substring(end);
          from = end + suffix.length();
        } else {
          from = end;
        }
      }
    }
    return result;
  }
}
