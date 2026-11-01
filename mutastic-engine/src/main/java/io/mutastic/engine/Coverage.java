package io.mutastic.engine;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.LineNumber;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The coverage pass: which tests reach which lines, and how long each test takes. It doubles as
/// the baseline, since every test runs.
final class Coverage {
  private static final ClassDesc PROBES = ClassDesc.of("io.mutastic.worker.Probes");
  private static final MethodTypeDesc LINE = MethodTypeDesc.of(CD_void, CD_int);

  private final ClassFile context;
  private final Map<String, Integer> lineIds = new HashMap<>();
  private final Map<String, Set<Integer>> linesByTest = new HashMap<>();
  private final Map<String, Duration> durations = new HashMap<>();

  Coverage(ClassFile context) {
    this.context = context;
  }

  /// The class with a probe at every line of every method that can have mutants.
  byte[] instrument(ClassModel model) {
    var className = model.thisClass().asInternalName();
    return context.transformClass(model, (classBuilder, element) -> {
      if (element instanceof MethodModel method && method.code().isPresent()) {
        classBuilder.transformMethod(method, MethodTransform.transformingCode(probes(className, method)));
      } else {
        classBuilder.with(element);
      }
    });
  }

  void recordTest(String test, Duration took) {
    durations.put(test, took);
  }

  void recordLines(String test, Set<Integer> lines) {
    linesByTest.computeIfAbsent(test, ignored -> new HashSet<>()).addAll(lines);
  }

  /// Leaves `tests` out: no mutant runs them.
  void exclude(List<String> tests) {
    tests.forEach(test -> {
      durations.remove(test);
      linesByTest.remove(test);
    });
  }

  Duration timeOf(String test) {
    return durations.getOrDefault(test, Duration.ZERO);
  }

  int testCount() {
    return durations.size();
  }

  /// The tests that reach the mutant's line, fastest first, so a catch is found soonest.
  List<String> coveringTests(Mutant mutant) {
    var lineId = lineIds.get(key(mutant.className(), mutant.methodName() + mutant.descriptor(), mutant.line()));
    if (lineId == null) {
      return List.of();
    }
    return linesByTest.entrySet().stream()
        .filter(entry -> entry.getValue().contains(lineId) && durations.containsKey(entry.getKey()))
        .map(Map.Entry::getKey)
        .sorted(Comparator.comparing((String test) -> durations.get(test)).thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  /// What these tests took between them in the coverage pass.
  Duration timeOf(List<String> tests) {
    return tests.stream().map(test -> durations.getOrDefault(test, Duration.ZERO)).reduce(Duration.ZERO, Duration::plus);
  }

  private CodeTransform probes(String className, MethodModel method) {
    var methodKey = method.methodName().stringValue() + method.methodType().stringValue();
    return (builder, element) -> {
      builder.with(element);
      if (element instanceof LineNumber lineNumber) {
        builder.loadConstant(lineId(key(className, methodKey, lineNumber.line())));
        builder.invokestatic(PROBES, "line", LINE);
      }
    };
  }

  private int lineId(String key) {
    return lineIds.computeIfAbsent(key, ignored -> lineIds.size());
  }

  /// Keyed by method as well as line: a record's header line holds its constructor, accessors
  /// and generated methods, so a test that only constructs one would otherwise "cover" them all.
  private static String key(String className, String method, int line) {
    return className + "#" + method + ":" + line;
  }
}
