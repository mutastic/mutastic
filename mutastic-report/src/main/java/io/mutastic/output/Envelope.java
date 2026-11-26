package io.mutastic.output;

import java.util.LinkedHashMap;
import java.util.Map;

/// An event stream's envelope, as `mutastic.events/v1` has it: one line of JSON per event, which
/// starts with the stream's `schema`, the `edition` that wrote it and the event's `reason`, then
/// the event's own fields in order. A reader keys on `reason`, and checks `schema` once.
public final class Envelope {
  private Envelope() {}

  /// The event `reason`, with `fields` after the envelope's, as a line ending in a newline.
  public static String line(
      String schema, String edition, String reason, Map<String, ? extends Object> fields) {
    var event = new LinkedHashMap<String, Object>();
    event.put("schema", schema);
    event.put("edition", edition);
    event.put("reason", reason);
    event.putAll(fields);
    return Json.write(event) + "\n";
  }
}
