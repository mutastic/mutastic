package com.example.rates;

/// An opening window in days: from `start` (null when it's always been open) up to `end`.
public record Window(Integer start, long end) {
  public boolean openOn(int day) {
    return (start == null || start <= day) && day < end;
  }
}
