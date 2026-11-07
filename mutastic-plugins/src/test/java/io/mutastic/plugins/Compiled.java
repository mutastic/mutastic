package io.mutastic.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/// A fixture's source files compiled in memory, and loaded on request, each load a class of its
/// own.
final class Compiled {
  private final Map<String, byte[]> classes;

  private Compiled(Map<String, byte[]> classes) {
    this.classes = classes;
  }

  /// `sources` by file name, `a/b/C.java`.
  static Compiled of(Map<String, String> sources) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var output = new HashMap<String, ByteArrayOutputStream>();
    try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
      var units =
          sources.entrySet().stream()
              .map(entry -> (JavaFileObject) new Source(entry.getKey(), entry.getValue()))
              .toList();
      var task =
          compiler.getTask(
              null, new Output(files, output), diagnostics, List.of("-proc:none"), null, units);
      assertThat(task.call()).as(diagnostics.getDiagnostics().toString()).isTrue();
    }
    var classes = new HashMap<String, byte[]>();
    output.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
    return new Compiled(classes);
  }

  ClassModel model(String name) {
    return ClassFile.of().parse(classes.get(name));
  }

  /// `name` loaded with `mutated` as its bytes, and every other class as compiled.
  Class<?> load(String name, byte[] mutated) throws ClassNotFoundException {
    var all = new HashMap<>(classes);
    all.put(name, mutated);
    return new ClassLoader(Compiled.class.getClassLoader()) {
      @Override
      protected Class<?> findClass(String wanted) throws ClassNotFoundException {
        var bytes = all.get(wanted);
        if (bytes == null) {
          throw new ClassNotFoundException(wanted);
        }
        return defineClass(wanted, bytes, 0, bytes.length);
      }
    }.loadClass(name);
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(String file, String text) {
      super(URI.create("string:///" + file), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  private static final class Output extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, ByteArrayOutputStream> output;

    Output(StandardJavaFileManager files, Map<String, ByteArrayOutputStream> output) {
      super(files);
      this.output = output;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        JavaFileManager.Location location,
        String className,
        JavaFileObject.Kind kind,
        FileObject sibling) {
      var bytes = new ByteArrayOutputStream();
      output.put(className, bytes);
      return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
        @Override
        public OutputStream openOutputStream() {
          return bytes;
        }
      };
    }
  }
}
