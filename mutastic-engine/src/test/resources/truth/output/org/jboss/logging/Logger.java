package org.jboss.logging;

public abstract class Logger {
  public abstract void infof(String format, Object... parameters);

  public abstract boolean isTraceEnabled();

  public abstract void tracef(String format, Object parameter);
}
