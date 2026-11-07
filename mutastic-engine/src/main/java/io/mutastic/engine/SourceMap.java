package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Where kotlinc's made-up line numbers really are. A class's lines run to the end of its file;
/// past that, each number stands for a line of code kotlinc copied in from an inline function, and
/// the `SourceDebugExtension` attribute (an SMAP, JSR-45) says which file and line that is. Read
/// only for what `@kotlin.Metadata` says kotlinc compiled: a class from anything else has lines as
/// they are.
final class SourceMap {
  private static final SourceMap NONE = new SourceMap("", List.of());

  /// `774#2,4:56`: four lines of file 2 from its line 774, numbered from 56 here.
  private static final Pattern RANGE =
      Pattern.compile(
          "(?<in>\\d+)(#(?<file>\\d+))?(,(?<repeat>\\d+))?:(?<out>\\d+)(,(?<step>\\d+))?");

  /// `+ 2 _Collections.kt`.
  private static final Pattern FILE = Pattern.compile("\\+ (?<id>\\d+) (?<name>.+)");

  /// A file the map names: the class it gives for it, and the file's own name.
  private record SourceFile(String owner, String name) {}

  /// Where a line really is.
  ///
  /// @param owner the class the SMAP names for its file,
  ///   `kotlin/collections/CollectionsKt___CollectionsKt`
  /// @param file the file's own name, `_Collections.kt`
  /// @param line the line in that file
  record Place(String owner, String file, int line) {
    /// `probe/Helpers.kt`: where the file is under a source root, going by its class's package.
    String path() {
      int slash = owner.lastIndexOf('/');
      return slash < 0 ? file : owner.substring(0, slash + 1) + file;
    }
  }

  /// Lines `out` to `out + count - 1` here are the lines from `in` of `owner`'s file.
  private record Range(int in, String owner, String file, int count, int out) {
    boolean holds(int line) {
      return line >= out && line < out + count;
    }

    /// Whether this range is a copy of `line` of `owner`'s file.
    boolean copies(String wanted, int line) {
      return owner.equals(wanted) && line >= in && line < in + count;
    }
  }

  private final String self;
  private final List<Range> ranges;

  private SourceMap(String self, List<Range> ranges) {
    this.self = self;
    this.ranges = List.copyOf(ranges);
  }

  /// The class's map: empty if kotlinc didn't compile it, or left nothing to map.
  static SourceMap of(ClassModel model) {
    if (!KotlinGenerated.isKotlin(model)) {
      return NONE;
    }
    return model
        .findAttribute(Attributes.sourceDebugExtension())
        .map(
            attribute ->
                parse(
                    model.thisClass().asInternalName(),
                    new String(attribute.contents(), StandardCharsets.UTF_8)))
        .orElse(NONE);
  }

  /// Reads the `Kotlin` stratum: its `*F` section names the files, its `*L` section the ranges. The
  /// `KotlinDebug` stratum after it says which call each copy is for, which nothing here needs.
  static SourceMap parse(String self, String smap) {
    var lines = kotlinStratum(smap);
    int fileSection = lines.indexOf("*F");
    int lineSection = lines.indexOf("*L");
    if (fileSection < 0 || lineSection < fileSection) {
      return NONE;
    }
    var files = files(lines.subList(fileSection + 1, lineSection));
    return new SourceMap(self, ranges(lines.subList(lineSection + 1, lines.size()), files));
  }

  /// What's between `*S Kotlin` and the next stratum, or the end.
  private static List<String> kotlinStratum(String smap) {
    return smap.lines()
        .dropWhile(line -> !line.equals("*S Kotlin"))
        .skip(1)
        .takeWhile(line -> !line.startsWith("*S") && !line.equals("*E"))
        .toList();
  }

  /// Each file by its number, from a pair of lines: `+ 2 _Collections.kt`, then its class.
  private static Map<String, SourceFile> files(List<String> section) {
    var files = new HashMap<String, SourceFile>();
    for (int at = 0; at + 1 < section.size(); at++) {
      var named = FILE.matcher(section.get(at));
      if (named.matches()) {
        files.put(named.group("id"), new SourceFile(section.get(at + 1), named.group("name")));
      }
    }
    return files;
  }

  /// A range with no file number is in the file of the one before.
  private static List<Range> ranges(List<String> section, Map<String, SourceFile> files) {
    var ranges = new ArrayList<Range>();
    var lastFile = "";
    for (String line : section) {
      var range = RANGE.matcher(line);
      if (range.matches()) {
        lastFile = range.group("file") == null ? lastFile : range.group("file");
        var file = files.get(lastFile);
        if (file != null) {
          ranges.add(range(range, file));
        }
      }
    }
    return ranges;
  }

  private static Range range(Matcher range, SourceFile file) {
    int repeat = range.group("repeat") == null ? 1 : Integer.parseInt(range.group("repeat"));
    int step = range.group("step") == null ? 1 : Integer.parseInt(range.group("step"));
    return new Range(
        Integer.parseInt(range.group("in")),
        file.owner(),
        file.name(),
        repeat * step,
        Integer.parseInt(range.group("out")));
  }

  /// Where the line in this class's line table really is, when the map says it's another line.
  /// Empty for a line that's its own: all of them, in a class with no map.
  Optional<Place> copied(int line) {
    return ranges.stream()
        .filter(range -> range.holds(line))
        .findFirst()
        .filter(range -> !range.owner().equals(self) || range.in() != range.out())
        .map(range -> new Place(range.owner(), range.file(), range.in() + line - range.out()));
  }

  /// Whether this class holds a copy of that line of `owner`'s file.
  boolean holdsCopyOf(String owner, int line) {
    return ranges.stream()
        .filter(range -> !range.owner().equals(self) || range.in() != range.out())
        .anyMatch(range -> range.copies(owner, line));
  }
}
