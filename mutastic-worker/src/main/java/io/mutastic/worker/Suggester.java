package io.mutastic.worker;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Finds a test that would catch a missed mutant by running it: calls the method as it is today
/// and with the mutant swapped in, on candidate inputs, and keeps the first input where they
/// disagree. The expected value is what the code does today, which the person must check is
/// what they meant.
///
/// Only methods whose parameters are all `int` or `long`, static or on a class with a
/// no-argument constructor, returning a primitive, a `String` or a boxed primitive: values that
/// can be written in a test as literals.
final class Suggester {
  /// How long one call may take, and all the calls for one mutant together.
  private static final long CALL_MILLIS = 200;
  private static final long BUDGET_MILLIS = 1000;

  /// What was found. `stuck` means a call on the mutant didn't return: its thread is still
  /// running, so the worker should be replaced.
  record Found(Optional<List<Long>> inputs, String today, String mutated, boolean stuck) {}

  private Suggester() {}

  static Found find(ProductionLoader production, String className, String methodName, String descriptor,
      byte[] mutantBytes, List<List<Long>> candidates) throws Exception {
    var none = new Found(Optional.empty(), "", "", false);
    var method = method(production.loadClass(className), methodName, descriptor);
    if (method.isEmpty()) {
      return none;
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS);
    var today = new ArrayList<String>();
    for (List<Long> inputs : candidates) {
      if (System.nanoTime() > deadline) {
        break;
      }
      today.add(call(method.get(), inputs).orElse(null));
    }
    production.swapIn(Map.of(className, mutantBytes));
    try {
      for (int index = 0; index < today.size(); index++) {
        if (today.get(index) == null) {
          continue; // the original didn't return in time: no expected value to write
        }
        var mutated = call(method.get(), candidates.get(index));
        if (mutated.isEmpty()) {
          return new Found(Optional.of(candidates.get(index)), today.get(index), "doesn't return", true);
        }
        if (!Objects.equals(today.get(index), mutated.get())) {
          return new Found(Optional.of(candidates.get(index)), today.get(index), mutated.get(), false);
        }
      }
      return none;
    } finally {
      production.swapBack(java.util.Set.of(className));
    }
  }

  private static Optional<Method> method(Class<?> owner, String name, String descriptor) {
    for (Method method : owner.getDeclaredMethods()) {
      var type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
      boolean wholeNumbers = java.util.Arrays.stream(method.getParameterTypes())
          .allMatch(parameter -> parameter == int.class || parameter == long.class);
      boolean reachable = Modifier.isStatic(method.getModifiers()) || hasNoArgumentConstructor(owner);
      if (method.getName().equals(name) && type.toMethodDescriptorString().equals(descriptor) && wholeNumbers
          && reachable && writable(method.getReturnType())) {
        method.setAccessible(true);
        return Optional.of(method);
      }
    }
    return Optional.empty();
  }

  /// The call's result as Java source (`10`, `10L`, `true`, `"text"`, `null`), `throws
  /// IllegalStateException`, or empty if it didn't return in time. A fresh instance per call,
  /// so one call's state can't change another's result.
  private static Optional<String> call(Method method, List<Long> inputs) {
    var arguments = new Object[inputs.size()];
    var types = method.getParameterTypes();
    for (int index = 0; index < inputs.size(); index++) {
      arguments[index] = types[index] == int.class ? (Object) inputs.get(index).intValue() : inputs.get(index);
    }
    // A daemon thread rather than an executor, whose close() would wait forever on a loop.
    var task = new FutureTask<>(() -> {
      var receiver = Modifier.isStatic(method.getModifiers()) ? null : construct(method.getDeclaringClass());
      return method.invoke(receiver, arguments);
    });
    var thread = new Thread(task, "mutastic-suggestion");
    thread.setDaemon(true);
    thread.start();
    try {
      return Optional.of(literal(task.get(CALL_MILLIS, TimeUnit.MILLISECONDS)));
    } catch (ExecutionException e) {
      var thrown = e.getCause() instanceof InvocationTargetException invoked ? invoked.getCause() : e.getCause();
      return Optional.of("throws " + thrown.getClass().getName());
    } catch (TimeoutException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private static Object construct(Class<?> owner) throws Exception {
    var constructor = owner.getDeclaredConstructor();
    constructor.setAccessible(true);
    return constructor.newInstance();
  }

  static String literal(Object value) {
    // Java 17: no patterns in switch.
    if (value == null) {
      return "null";
    }
    if (value instanceof String text) {
      return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
          .replace("\t", "\\t") + '"'; // and so never a tab, which the protocol splits on
    }
    if (value instanceof Long number) {
      return number + "L";
    }
    if (value instanceof Float number) {
      return number + "f";
    }
    if (value instanceof Character character) {
      return "'" + (character == '\'' ? "\\'" : character == '\\' ? "\\\\" : character.toString()) + "'";
    }
    return String.valueOf(value);
  }

  private static boolean writable(Class<?> type) {
    return type != void.class && (type.isPrimitive() || type == String.class || type == Integer.class
        || type == Long.class || type == Boolean.class || type == Short.class || type == Byte.class
        || type == Character.class || type == Double.class || type == Float.class);
  }

  private static boolean hasNoArgumentConstructor(Class<?> owner) {
    try {
      owner.getDeclaredConstructor();
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }
}
