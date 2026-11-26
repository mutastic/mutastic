package com.example.cache;

/// What a framework writes for `$Cache`, as Micronaut 4 writes a `$Bean$Definition` for each bean:
/// marked @Generated in the class file, so its mutants are a tool's, not the code's.
@Generated
public final class $Cache$Definition {
  public int capacity(int requested) {
    return requested > 0 ? requested : 1;
  }

  public boolean isEnabled(boolean configured) {
    return configured && capacity(1) > 0;
  }
}
