package io.mutastic.engine;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
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

/// The coverage pass: which tests reach which lines, what whole numbers they pass to each method,
/// and how long each test takes. It doubles as the baseline, since every test runs.
final class Coverage {
  /// What instrumented code calls: the worker's probes.
  static final ClassDesc WORKER_PROBES = ClassDesc.of("io.mutastic.worker.Probes");

  private static final MethodTypeDesc LINE = MethodTypeDesc.of(CD_void, CD_int);
  private static final MethodTypeDesc VALUE = MethodTypeDesc.of(CD_void, CD_int, CD_long);

  /// One recorded parameter: whose, and its name in the source (or `arg0` without debug info).
  private record Parameter(String className, String method, String name) {}

  private final List<Parameter> parameters = new java.util.ArrayList<>();
  private final Map<String, Map<Integer, Set<Long>>> valuesByTest = new HashMap<>();

  private final ClassFile context;
  private final ClassDesc probes;
  private final Map<String, Integer> lineIds = new HashMap<>();
  private final Map<String, Set<Integer>> linesByTest = new HashMap<>();
  private final Map<String, Duration> durations = new HashMap<>();

  Coverage(ClassFile context) {
    this(context, WORKER_PROBES);
  }

  /// `probes` is the class instrumented code calls; tests pass their own stand-in.
  Coverage(ClassFile context, ClassDesc probes) {
    this.context = context;
    this.probes = probes;
  }

  /// The class with a probe at every line of every method that can have mutants.
  byte[] instrument(ClassModel model) {
    var className = model.thisClass().asInternalName();
    return context.transformClass(
        model,
        (classBuilder, element) -> {
          if (element instanceof MethodModel method && method.code().isPresent()) {
            classBuilder.transformMethod(
                method, MethodTransform.transformingCode(probes(className, method)));
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

  void recordValues(String test, int parameter, Set<Long> values) {
    valuesByTest
        .computeIfAbsent(test, ignored -> new HashMap<>())
        .computeIfAbsent(parameter, ignored -> new HashSet<>())
        .addAll(values);
  }

  /// The names of the mutant's method's whole-number parameters, in declaration order.
  List<String> parameterNames(Mutant mutant) {
    var method = mutant.methodName() + mutant.descriptor();
    return parameters.stream()
        .filter(
            parameter ->
                parameter.className().equals(mutant.className())
                    && parameter.method().equals(method))
        .map(Parameter::name)
        .toList();
  }

  /// The whole numbers `tests` passed to the mutant's method, by parameter name in declaration
  /// order: `total` → 50, 250. Empty for a method without whole-number parameters.
  Map<String, List<Long>> inputs(Mutant mutant, List<String> tests) {
    var method = mutant.methodName() + mutant.descriptor();
    var seen = new java.util.LinkedHashMap<String, java.util.TreeSet<Long>>();
    for (int id = 0; id < parameters.size(); id++) {
      var parameter = parameters.get(id);
      if (parameter.className().equals(mutant.className()) && parameter.method().equals(method)) {
        var values = seen.computeIfAbsent(parameter.name(), name -> new java.util.TreeSet<>());
        for (String test : tests) {
          values.addAll(valuesByTest.getOrDefault(test, Map.of()).getOrDefault(id, Set.of()));
        }
      }
    }
    var inputs = new java.util.LinkedHashMap<String, List<Long>>();
    seen.forEach(
        (name, values) -> {
          if (!values.isEmpty()) {
            inputs.put(name, List.copyOf(values));
          }
        });
    return inputs;
  }

  /// Leaves `tests` out: no mutant runs them.
  void exclude(List<String> tests) {
    tests.forEach(
        test -> {
          durations.remove(test);
          linesByTest.remove(test);
          valuesByTest.remove(test);
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
    var lineId =
        lineIds.get(
            key(mutant.className(), mutant.methodName() + mutant.descriptor(), mutant.line()));
    if (lineId == null) {
      return List.of();
    }
    return linesByTest.entrySet().stream()
        .filter(entry -> entry.getValue().contains(lineId) && durations.containsKey(entry.getKey()))
        .map(Map.Entry::getKey)
        .sorted(
            Comparator.comparing((String test) -> durations.get(test))
                .thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  /// What these tests took between them in the coverage pass.
  Duration timeOf(List<String> tests) {
    return tests.stream()
        .map(test -> durations.getOrDefault(test, Duration.ZERO))
        .reduce(Duration.ZERO, Duration::plus);
  }

  private CodeTransform probes(String className, MethodModel method) {
    var methodKey = method.methodName().stringValue() + method.methodType().stringValue();
    var recorded = wholeNumberParameters(className, method, methodKey);
    return new CodeTransform() {
      @Override
      public void atStart(CodeBuilder builder) {
        passParameterValues(builder, recorded);
      }

      @Override
      public void accept(CodeBuilder builder, CodeElement element) {
        builder.with(element);
        if (element instanceof LineNumber lineNumber) {
          builder.loadConstant(lineId(key(className, methodKey, lineNumber.line())));
          builder.invokestatic(probes, "line", LINE);
        }
      }
    };
  }

  /// Passes each recorded parameter's value to the probes, widened to a `long`.
  private void passParameterValues(CodeBuilder builder, List<int[]> recorded) {
    for (int[] parameter : recorded) { // {id, slot, 1 if long}
      builder.loadConstant(parameter[0]);
      if (parameter[2] == 1) {
        builder.lload(parameter[1]);
      } else {
        builder.iload(parameter[1]).i2l();
      }
      builder.invokestatic(probes, "value", VALUE);
    }
  }

  /// The method's `int`, `long`, `short`, `byte` and `char` parameters, each as {id, slot, 1 if
  /// long}. Booleans are left out: two values tell nothing an input list would.
  private List<int[]> wholeNumberParameters(
      String className, MethodModel method, String methodKey) {
    var names = parameterNames(method);
    var recorded = new java.util.ArrayList<int[]>();
    int slot = method.flags().has(java.lang.reflect.AccessFlag.STATIC) ? 0 : 1;
    var types = method.methodTypeSymbol().parameterList();
    for (int index = 0; index < types.size(); index++) {
      var descriptor = types.get(index).descriptorString();
      if ("IJSBC".contains(descriptor) && descriptor.length() == 1) {
        var parameter =
            new Parameter(className, methodKey, names.getOrDefault(slot, "arg" + index));
        int id = parameters.indexOf(parameter);
        if (id < 0) {
          parameters.add(parameter);
          id = parameters.size() - 1;
        }
        recorded.add(new int[] {id, slot, descriptor.equals("J") ? 1 : 0});
      }
      slot += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
    }
    return recorded;
  }

  /// Names from the LocalVariableTable, which javac writes unless debug info is off.
  private static Map<Integer, String> parameterNames(MethodModel method) {
    var names = new HashMap<Integer, String>();
    method
        .code()
        .flatMap(code -> code.findAttribute(java.lang.classfile.Attributes.localVariableTable()))
        .ifPresent(
            table ->
                table.localVariables().stream()
                    .filter(variable -> variable.startPc() == 0)
                    .forEach(
                        variable -> names.put(variable.slot(), variable.name().stringValue())));
    return names;
  }

  private int lineId(String key) {
    return lineIds.computeIfAbsent(key, ignored -> lineIds.size());
  }

  /// Keyed by method as well as line: a record's header line holds its constructor, accessors and
  /// generated methods, so a test that only constructs one would otherwise "cover" them all.
  private static String key(String className, String method, int line) {
    return className + "#" + method + ":" + line;
  }
}
