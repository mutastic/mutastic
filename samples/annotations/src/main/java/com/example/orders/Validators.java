package com.example.orders;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/// A validator kept for the life of the JVM, as an application keeps one: it reads a class's
/// annotations once.
public final class Validators {
  public static final Validator SHARED = Validation.buildDefaultValidatorFactory().getValidator();

  private Validators() {}
}
