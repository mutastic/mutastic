package io.mutastic.plugins;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.ClassMutator.Member;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/// Compiles proposals for one class, with the same compiler and settings as its own source, and
/// says what each changes: what javac makes of a source file depends on the version and the flags,
/// so the class's own file compiled here, not the class file the build made, is what a proposal is
/// compared with.
final class ProposalCompiler {
  /// What a class file's major version is more than the Java release it's for.
  private static final int RELEASE_TO_MAJOR = 44;

  private static final int OLDEST_RELEASE = 8;

  /// What compares two compilations: no line numbers or variable names, and a constant pool of its
  /// own for each method, in the order the method uses it.
  private static final ClassFile COMPARING =
      ClassFile.of(
          ClassFile.DebugElementsOption.DROP_DEBUG, ClassFile.LineNumbersOption.DROP_LINE_NUMBERS);

  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(?<enclosing>.+)\\$\\d+");

  private final ClassModel owner;
  private final List<Path> sources;
  private final List<Path> classpath;
  private Optional<Map<String, byte[]>> baseline = Optional.empty();

  ProposalCompiler(ClassModel owner, List<Path> sources, List<Path> classpath) {
    this.owner = owner;
    this.sources = sources;
    this.classpath = classpath;
  }

  /// What a proposal is.
  ///
  /// @param member what it changes, or the class when it can't be run
  /// @param skipped why it shouldn't run
  /// @param bytes the class as it compiles, when it can run
  /// @param fingerprint what it compiles to, once it has compiled to one method's change
  record Judged(
      Member member,
      Optional<String> skipped,
      Optional<byte[]> bytes,
      Optional<String> fingerprint) {
    static Judged unviable(String why) {
      return skipped("unviable: " + why);
    }

    static Judged skipped(String why) {
      return new Judged(Member.ofClass(), Optional.of(why), Optional.empty(), Optional.empty());
    }
  }

  Judged judge(String source) {
    var fileName =
        owner
            .findAttribute(Attributes.sourceFile())
            .map(attribute -> attribute.sourceFile().stringValue())
            .orElse("");
    if (!fileName.endsWith(".java")) {
      return Judged.unviable("a proposal is Java source, and this class wasn't compiled from Java");
    }
    var original = originalSource(fileName);
    if (original.isEmpty()) {
      return Judged.unviable("the class's own source isn't here to compare a proposal with");
    }
    var compiled = compile(fileName, source);
    if (compiled instanceof Compilation.Failed failed) {
      return Judged.unviable("it doesn't compile: " + failed.why());
    }
    var own = baseline(fileName, original.get());
    if (own instanceof Compilation.Failed failed) {
      return Judged.unviable("the class's own source doesn't compile here: " + failed.why());
    }
    return compared(((Compilation.Done) own).classes(), ((Compilation.Done) compiled).classes());
  }

  private Compilation baseline(String fileName, String source) {
    if (baseline.isPresent()) {
      return new Compilation.Done(baseline.get());
    }
    var compiled = compile(fileName, source);
    if (compiled instanceof Compilation.Done done) {
      baseline = Optional.of(done.classes());
    }
    return compiled;
  }

  private Judged compared(Map<String, byte[]> own, Map<String, byte[]> proposed) {
    var name = owner.thisClass().asInternalName();
    if (!own.keySet().equals(proposed.keySet())) {
      return Judged.unviable("it makes different classes from the source it replaces");
    }
    for (var each : own.entrySet()) {
      if (!each.getKey().equals(name)
          && !normalised(each.getValue())
              .equals(normalised(requireNonNull(proposed.get(each.getKey()))))) {
        return Judged.unviable("it changes another class in the file too: " + each.getKey());
      }
    }
    if (!own.containsKey(name)) {
      return Judged.unviable("its source doesn't make the class " + name);
    }
    var bytes = requireNonNull(proposed.get(name));
    var before = COMPARING.parse(requireNonNull(own.get(name)));
    var after = COMPARING.parse(bytes);
    if (!shape(before).equals(shape(after))) {
      return Judged.unviable("its fields or methods aren't the class's");
    }
    return ofChange(before, after, bytes);
  }

  private static Judged ofChange(ClassModel before, ClassModel after, byte[] bytes) {
    var changed = changedMethods(before, after);
    if (changed.isEmpty()) {
      return Judged.skipped("equivalent: it compiles to the same code as the class");
    }
    if (changed.size() > 1) {
      return Judged.unviable(
          "it changes more than one method (%s): a mutant is one change"
              .formatted(String.join(", ", changed)));
    }
    var method = changed.iterator().next();
    var member = member(after, method);
    if (member.isEmpty() || method.startsWith("<clinit>")) {
      return Judged.unviable("it changes a static initialiser, which only a class's load runs");
    }
    return new Judged(
        member.get(),
        Optional.empty(),
        Optional.of(bytes),
        Optional.of(sha256(normalisedMethods(after))));
  }

  private static Optional<Member> member(ClassModel model, String method) {
    return model.methods().stream()
        .filter(each -> key(each).equals(method))
        .findFirst()
        .map(Member::ofMethod);
  }

  /// The methods whose code differs, a lambda's counted as the method it's written in's.
  private static Set<String> changedMethods(ClassModel before, ClassModel after) {
    var was = methodCode(before);
    var is = methodCode(after);
    var changed = new TreeSet<String>();
    is.forEach(
        (method, code) -> {
          if (!code.equals(was.get(method))) {
            changed.add(enclosing(method, after));
          }
        });
    was.keySet().stream()
        .filter(method -> !is.containsKey(method))
        .forEach(method -> changed.add(enclosing(method, before)));
    return changed;
  }

  /// `lambda$price$0()I`'s method, `price(I)I`, when that name is unique, as overloads share one.
  private static String enclosing(String method, ClassModel model) {
    var lambda = LAMBDA.matcher(method.substring(0, method.indexOf('(')));
    if (!lambda.matches()) {
      return method;
    }
    var named =
        model.methods().stream()
            .filter(each -> each.methodName().equalsString(lambda.group("enclosing")))
            .map(ProposalCompiler::key)
            .toList();
    return named.size() == 1 ? named.getFirst() : method;
  }

  private static Map<String, String> methodCode(ClassModel model) {
    var code = new HashMap<String, String>();
    for (MethodModel method : model.methods()) {
      code.put(key(method), methodFingerprint(model, method));
    }
    return code;
  }

  private static String key(MethodModel method) {
    return method.methodName().stringValue() + method.methodType().stringValue();
  }

  /// The method alone in a class of its own, so nothing outside it shows.
  private static String methodFingerprint(ClassModel model, MethodModel method) {
    return HexFormat.of()
        .formatHex(
            COMPARING.build(
                model.thisClass().asSymbol(),
                builder -> {
                  model
                      .superclass()
                      .ifPresent(superclass -> builder.withSuperclass(superclass.asSymbol()));
                  builder.withMethod(
                      method.methodName().stringValue(),
                      method.methodTypeSymbol(),
                      method.flags().flagsMask(),
                      to -> method.code().ifPresent(to::with));
                }));
  }

  private static String normalisedMethods(ClassModel model) {
    return methodCode(model).entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + entry.getValue())
        .collect(Collectors.joining());
  }

  private static String normalised(byte[] classBytes) {
    return normalisedMethods(COMPARING.parse(classBytes));
  }

  /// What a caller and the JVM see of the class: everything but its methods' code, and the
  /// compiler's own methods, which come and go with lambdas.
  private static List<String> shape(ClassModel model) {
    var shape = new ArrayList<String>();
    shape.add(model.superclass().map(entry -> entry.asInternalName()).orElse(""));
    model.interfaces().forEach(each -> shape.add(each.asInternalName()));
    for (FieldModel field : model.fields()) {
      shape.add(
          "%s %s %d".formatted(field.fieldName(), field.fieldType(), field.flags().flagsMask()));
    }
    model.methods().stream()
        .filter(method -> !method.flags().has(AccessFlag.SYNTHETIC))
        .forEach(method -> shape.add(key(method) + " " + method.flags().flagsMask()));
    return shape.stream().sorted().toList();
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }

  private Optional<String> originalSource(String fileName) {
    var internal = owner.thisClass().asInternalName();
    var directory = internal.contains("/") ? internal.substring(0, internal.lastIndexOf('/')) : "";
    return sources.stream()
        .map(root -> root.resolve(directory).resolve(fileName))
        .filter(Files::isRegularFile)
        .findFirst()
        .map(
            file -> {
              try {
                return Files.readString(file);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  /// What a compiler made of a source file.
  private sealed interface Compilation {
    /// @param classes each class by internal name
    record Done(Map<String, byte[]> classes) implements Compilation {}

    record Failed(String why) implements Compilation {}
  }

  private Compilation compile(String fileName, String source) {
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return new Compilation.Failed("this JVM has no Java compiler: it needs a JDK");
    }
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var output = new LinkedHashMap<String, ByteArrayOutputStream>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      var manager = new InMemoryOutput(files, output);
      var unit = List.of(new SourceText(fileName, source));
      if (!compiler.getTask(null, manager, diagnostics, options(), null, unit).call()) {
        return new Compilation.Failed(firstError(diagnostics));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var classes = new LinkedHashMap<String, byte[]>();
    output.forEach((name, bytes) -> classes.put(name.replace('.', '/'), bytes.toByteArray()));
    return new Compilation.Done(classes);
  }

  private List<String> options() {
    return List.of(
        "-proc:none",
        "-nowarn",
        "-g",
        "--release",
        String.valueOf(Math.max(OLDEST_RELEASE, owner.majorVersion() - RELEASE_TO_MAJOR)),
        "-classpath",
        classpath.stream()
            .map(Path::toString)
            .collect(Collectors.joining(java.io.File.pathSeparator)));
  }

  private static String firstError(DiagnosticCollector<JavaFileObject> diagnostics) {
    return diagnostics.getDiagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
        .findFirst()
        .map(
            diagnostic ->
                "line %d: %s"
                    .formatted(
                        diagnostic.getLineNumber(),
                        diagnostic
                            .getMessage(java.util.Locale.ROOT)
                            .lines()
                            .findFirst()
                            .orElse("")))
        .orElse("javac said no");
  }

  /// A source file that isn't on disk under the name it needs: javac holds a public class's name to
  /// its file's.
  private static final class SourceText extends SimpleJavaFileObject {
    private final String text;

    SourceText(String fileName, String text) {
      super(URI.create("string:///" + fileName), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  /// Keeps what javac writes.
  private static final class InMemoryOutput
      extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, ByteArrayOutputStream> output;

    InMemoryOutput(StandardJavaFileManager files, Map<String, ByteArrayOutputStream> output) {
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
