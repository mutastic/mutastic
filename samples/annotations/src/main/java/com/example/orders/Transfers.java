package com.example.orders;

import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

/// Validation of a method's parameters, which an annotation on the class turns on.
@Validated
public class Transfers {
  public String send(@NotNull String to, String memo) {
    return to + memo;
  }
}
