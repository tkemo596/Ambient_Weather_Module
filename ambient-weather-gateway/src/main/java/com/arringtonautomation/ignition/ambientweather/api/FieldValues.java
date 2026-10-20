package com.arringtonautomation.ignition.ambientweather.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

/**
 * Decides the OPC data type for each field and converts raw JSON values into it.
 *
 * <p>A field's type is fixed the first time it is seen with a non-null value, because an OPC
 * node's DataType shouldn't change underneath tags bound to it. Every JSON number becomes a
 * Double: the API sends {@code humidity} as 30 on one reading and 30.5 on another, so typing by
 * the first value would break later readings.
 */
public final class FieldValues {

  public enum Kind { NUMBER, TIMESTAMP, STRING, BOOLEAN }

  /** Numeric fields that hold a point in time rather than a measurement. */
  private static final Set<String> NUMERIC_TIMESTAMPS = Set.of("dateutc", "lightning_time");

  /** Below this, a numeric timestamp is taken as epoch seconds rather than milliseconds. */
  private static final long SECONDS_CUTOFF = 100_000_000_000L; // year 5138 in seconds; 1973 in ms

  private FieldValues() {}

  /** The type a field should get, or empty if the value is null (wait for a real value). */
  public static Optional<Kind> kindOf(String field, Object raw) {
    if (raw instanceof Boolean) {
      return Optional.of(Kind.BOOLEAN);
    }
    if (raw instanceof Number) {
      return Optional.of(NUMERIC_TIMESTAMPS.contains(field) ? Kind.TIMESTAMP : Kind.NUMBER);
    }
    if (raw instanceof String s) {
      return Optional.of(parseInstant(s).isPresent() ? Kind.TIMESTAMP : Kind.STRING);
    }
    return Optional.empty();
  }

  /**
   * Converts a raw value to the field's established kind: {@link Double}, {@link Instant},
   * {@link String} or {@link Boolean}. Empty when the value is null or can't be represented,
   * which the driver reports as bad quality rather than a wrong value.
   */
  public static Optional<Object> convert(Kind kind, Object raw) {
    if (raw == null) {
      return Optional.empty();
    }
    switch (kind) {
      case NUMBER:
        if (raw instanceof Number n) {
          return Optional.of(n.doubleValue());
        }
        if (raw instanceof Boolean b) {
          return Optional.of(b ? 1.0 : 0.0);
        }
        if (raw instanceof String s) {
          try {
            return Optional.of(Double.parseDouble(s.trim()));
          } catch (NumberFormatException e) {
            return Optional.empty();
          }
        }
        return Optional.empty();

      case TIMESTAMP:
        if (raw instanceof Number n) {
          long v = n.longValue();
          return Optional.of(v < SECONDS_CUTOFF ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v));
        }
        if (raw instanceof String s) {
          return parseInstant(s).map(i -> i);
        }
        return Optional.empty();

      case BOOLEAN:
        if (raw instanceof Boolean b) {
          return Optional.of(b);
        }
        if (raw instanceof Number n) {
          return Optional.of(n.doubleValue() != 0.0);
        }
        return Optional.empty();

      case STRING:
      default:
        return Optional.of(raw instanceof String s ? s : String.valueOf(raw));
    }
  }

  static Optional<Instant> parseInstant(String s) {
    String t = s.trim();
    // Cheap pre-check so plain strings ("America/Denver") don't pay for an exception.
    if (t.length() < 20 || t.charAt(4) != '-' || t.charAt(10) != 'T') {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(t));
    } catch (DateTimeParseException e) {
      try {
        return Optional.of(java.time.OffsetDateTime.parse(t).toInstant());
      } catch (DateTimeParseException e2) {
        return Optional.empty();
      }
    }
  }
}
