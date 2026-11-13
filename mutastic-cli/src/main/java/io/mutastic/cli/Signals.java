package io.mutastic.cli;

import java.lang.reflect.Proxy;

/// A signal handled, where the JVM lets it be: `sun.misc.Signal`, reached by reflection as it's not
/// part of the platform, so a JVM without it, or a signal the system hasn't got (`INFO` on Linux)
/// or the JVM keeps for itself, is handled by nothing and is never an error.
final class Signals {
  private Signals() {}

  /// Runs `then` each time the process gets the signal `name` (`USR1`, `INFO`, `TSTP`), in place of
  /// its default action; whether it will.
  static boolean on(String name, Runnable then) {
    try {
      var signal = Class.forName("sun.misc.Signal");
      var handler = Class.forName("sun.misc.SignalHandler");
      signal
          .getMethod("handle", signal, handler)
          .invoke(
              null, signal.getConstructor(String.class).newInstance(name), handling(handler, then));
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      return false;
    }
  }

  /// A `SignalHandler` that runs `then`.
  // A proxy is equal only to itself: `equals` by value would call the proxy's own `equals` again.
  @SuppressWarnings("ReferenceEquality")
  private static Object handling(Class<?> handler, Runnable then) {
    return Proxy.newProxyInstance(
        handler.getClassLoader(),
        new Class<?>[] {handler},
        (proxy, method, arguments) ->
            switch (method.getName()) {
              case "handle" -> {
                then.run();
                yield null;
              }
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> arguments != null && proxy == arguments[0];
              default -> "mutastic's handler";
            });
  }
}
