package com.arringtonautomation.ignition.ambientweather.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One weather station from {@code GET /v1/devices}: identity plus its most recent reading.
 *
 * @param macAddress station MAC address, as returned by the API (the stable identity)
 * @param name       user-assigned station name, may be empty
 * @param location   user-assigned location label, may be empty
 * @param lastData   raw field values from {@code lastData}, in API order
 */
public record Station(
    String macAddress,
    String name,
    String location,
    Map<String, Object> lastData) {

  public Station {
    name = name == null ? "" : name;
    location = location == null ? "" : location;
    lastData = Collections.unmodifiableMap(new LinkedHashMap<>(lastData == null ? Map.of() : lastData));
  }

  /** Time the station took this reading ({@code dateutc}), if present. */
  public Optional<Instant> readingTime() {
    Object v = lastData.get("dateutc");
    if (v instanceof Number n) {
      return Optional.of(Instant.ofEpochMilli(n.longValue()));
    }
    return Optional.empty();
  }

  /**
   * A tag-path-safe identifier derived from the MAC, e.g. {@code AA-BB-CC-DD-EE-FF}. Used for node
   * ids so tags survive a station being renamed on AmbientWeather.net.
   */
  public String nodeKey() {
    return macAddress.trim().toUpperCase().replace(':', '-');
  }

  /** Folder name shown when browsing: the station name if set, otherwise the MAC key. */
  public String displayName() {
    String n = name.isBlank() ? nodeKey() : name.trim();
    // Ignition tag paths reserve '/', '[' and ']'; keep the browse name tag-friendly.
    return n.replaceAll("[\\[\\]/\\\\]", "_");
  }
}
