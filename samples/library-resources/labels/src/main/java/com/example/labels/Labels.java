package com.example.labels;

import java.util.Locale;
import java.util.ResourceBundle;

/// Text for an app, from the app's own bundles.
public final class Labels {
  private Labels() {}

  /// `key` in the bundle `baseName` for `locale`: found by this library's class loader, as a
  /// library's lookups are.
  public static String text(String baseName, String key, Locale locale) {
    return ResourceBundle.getBundle(baseName, locale).getString(key);
  }
}
