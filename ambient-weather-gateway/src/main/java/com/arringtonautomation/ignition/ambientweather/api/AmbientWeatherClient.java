package com.arringtonautomation.ignition.ambientweather.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal client for the Ambient Weather REST API (https://ambientweather.docs.apiary.io).
 *
 * <p>Only {@code GET /v1/devices} is used: one call returns every station on the account with its
 * latest reading, which is all a live-data driver needs and stays well inside the rate limits
 * (1 request/s per apiKey, 3 requests/s per applicationKey).
 */
public final class AmbientWeatherClient {

  public static final String DEFAULT_BASE_URL = "https://rt.ambientweather.net/v1";

  private final HttpClient http;
  private final String baseUrl;
  private final String applicationKey;
  private final String apiKey;
  private final Duration timeout;

  public AmbientWeatherClient(String baseUrl, String applicationKey, String apiKey, Duration timeout) {
    this.baseUrl = stripTrailingSlash(
        baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim());
    this.applicationKey = requireKey(applicationKey, "applicationKey");
    this.apiKey = requireKey(apiKey, "apiKey");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.http = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /** Lists the account's stations, each with its most recent reading. */
  public List<Station> fetchStations() throws AmbientApiException, InterruptedException {
    URI uri = URI.create(baseUrl + "/devices"
        + "?applicationKey=" + enc(applicationKey)
        + "&apiKey=" + enc(apiKey));

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("User-Agent", "Ignition-AmbientWeatherDriver/1.0")
        .GET()
        .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // IOException messages from HttpClient don't include the query string, but be explicit
      // about what we surface: the exception type and a scrubbed message.
      String detail = scrub(e.getMessage());
      throw new AmbientApiException(
          "Request failed: " + e.getClass().getSimpleName()
              + (detail.isEmpty() ? "" : " – " + detail), e);
    }

    int status = response.statusCode();
    if (status != 200) {
      throw new AmbientApiException(describeStatus(status) + bodySnippet(response.body()), status);
    }

    try {
      return parseStations(response.body());
    } catch (IllegalArgumentException e) {
      throw new AmbientApiException("Unexpected response: " + e.getMessage(), e);
    }
  }

  /** Parses a {@code /v1/devices} response body. Package-visible logic, public for tests. */
  public static List<Station> parseStations(String body) {
    Object root = MiniJson.parse(body);
    if (!(root instanceof List<?> list)) {
      throw new IllegalArgumentException("expected a JSON array of devices");
    }
    List<Station> stations = new ArrayList<>(list.size());
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> device)) {
        continue;
      }
      Object mac = device.get("macAddress");
      if (!(mac instanceof String macAddress) || macAddress.isBlank()) {
        continue;
      }
      String name = "";
      String location = "";
      if (device.get("info") instanceof Map<?, ?> info) {
        name = info.get("name") instanceof String s ? s : "";
        location = info.get("location") instanceof String s ? s : "";
      }
      Map<String, Object> lastData = Map.of();
      if (device.get("lastData") instanceof Map<?, ?> ld) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) ld;
        lastData = typed;
      }
      stations.add(new Station(macAddress, name, location, lastData));
    }
    return stations;
  }

  private static String describeStatus(int status) {
    return switch (status) {
      case 401, 403 -> "HTTP " + status + " – check the Application Key and API Key";
      case 429 -> "HTTP 429 – rate limited by Ambient Weather";
      default -> status >= 500
          ? "HTTP " + status + " – Ambient Weather server error"
          : "HTTP " + status;
    };
  }

  private String bodySnippet(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String b = scrub(body.strip().replaceAll("\\s+", " "));
    return ": " + (b.length() > 200 ? b.substring(0, 200) + "…" : b);
  }

  /** Removes either key if a server or exception ever echoes it back. */
  private String scrub(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return text.replace(apiKey, "***").replace(applicationKey, "***");
  }

  private static String requireKey(String key, String name) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return key.trim();
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
