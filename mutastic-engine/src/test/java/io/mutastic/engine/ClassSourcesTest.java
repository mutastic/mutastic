package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Where a class's source is, as its class file says.
class ClassSourcesTest {
  @TempDir Path classes;

  /// kotlinc names `Tally.common.kt`'s class `Tally_commonKt`: only the class file says which file.
  @Test
  void theFileItsClassFileNames() throws Exception {
    write("com/example/tally/Tally_commonKt", "Tally.common.kt");
    write("Root", "Root.java");

    var sources = new ClassSources(List.of(classes));

    assertThat(sources.apply("com/example/tally/Tally_commonKt"))
        .contains("com/example/tally/Tally.common.kt");
    assertThat(sources.apply("Root")).contains("Root.java");
    assertThat(sources.apply("com/example/Missing")).isEmpty();
  }

  /// Without a `SourceFile` (`-g:lines`, or bytecode a tool wrote), the file is the top-level
  /// class's, as the nesting attributes say: never one cut from the name at its first `$`.
  @Test
  void withoutOneTheTopLevelClassNamesIt() throws Exception {
    var compiled =
        Javac.compile(
            List.of(
                new Javac.Source(
                    "cache.$Cache",
                    """
                    package cache;
                    public final class $Cache {
                      public static final class Entry {}
                      Runnable task() { return new Runnable() { public void run() {} }; }
                    }
                    """)),
            List.of(),
            "-g:lines");
    for (var each : compiled.entrySet()) {
      var file = classes.resolve(each.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, each.getValue());
    }

    var sources = new ClassSources(List.of(classes));

    assertThat(sources.apply("cache/$Cache")).contains("cache/$Cache.java");
    assertThat(sources.apply("cache/$Cache$Entry")).contains("cache/$Cache.java");
    assertThat(sources.apply("cache/$Cache$1")).contains("cache/$Cache.java");
  }

  private void write(String internalName, String sourceFile) throws Exception {
    var bytes =
        ClassFile.of()
            .build(
                ClassDesc.ofInternalName(internalName),
                builder -> builder.with(SourceFileAttribute.of(sourceFile)));
    var file = classes.resolve(internalName + ".class");
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }
}
