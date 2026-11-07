package com.example.orders;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/// A record's annotation is written once, and javac copies it to the field, the accessor and the
/// constructor's parameter.
public record Customer(@NotBlank String name, @Email String email) {}
