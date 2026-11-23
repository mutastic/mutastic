package io.mutastic.worker.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// An annotation with an element of every kind a class file holds, for a reader to skip.
@Retention(RetentionPolicy.RUNTIME)
public @interface Settings {
  int port();

  long timeout();

  double ratio();

  String name();

  Class<?> type();

  ElementType kind();

  Retention retention();

  String[] tags();
}
