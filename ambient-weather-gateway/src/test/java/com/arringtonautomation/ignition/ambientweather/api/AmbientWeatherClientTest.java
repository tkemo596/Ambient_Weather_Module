package com.arringtonautomation.ignition.ambientweather.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmbientWeatherClientTest {

  /** The example response from the Ambient Weather API docs, plus a second station. */
  static final String DEVICES_JSON = """
      [
        {
          "macAddress": "00:0e:c6:20:0f:7b",
          "info": { "name": "My Weather Station", "location": "Home" },
          "lastData": {
            "dateutc": 1515436500000,
            "date": "2018-01-08T18:35:00.000Z",
            "winddir": 58, "windspeedmph": 0.9, "windgustmph": 4, "maxdailygust": 5,
            "tempf": 66.9, "humidity": 30, "baromrelin": 30.05, "baromabsin": 28.71,
            "tempinf": 74.1, "humidityin": 30, "hourlyrainin": 0, "dailyrainin": 0,
            "feelsLike": 66.9, "dewPoint": 34.45380707462477,
            "battout": 1, "tz": "America/Denver",
            "lastRain": "2018-01-02T11:23:00.000Z"
          }
        },
        {
          "macAddress": "AA:BB:CC:DD:EE:FF",
          "info": { "name": "Shop/Roof [north]" },
          "lastData": { "dateutc": 1515436560000, "tempf": 12 }
        },
        { "info": { "name": "no mac, skipped" } }
      ]
      """;

  HttpServer server;
  String baseUrl;
  final AtomicReference<String> lastQuery = new AtomicReference<>();
  volatile int status = 200;
  volatile String body = DEVICES_JSON;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/devices", ex -> {
      lastQuery.set(ex.getRequestURI().getRawQuery());
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  AmbientWeatherClient client() {
    return new AmbientWeatherClient(baseUrl, "app key&1", "api-key-secret", Duration.ofSeconds(5));
  }

  @Test
  void fetchesAndParsesStations() throws Exception {
    List<Station> stations = client().fetchStations();

    assertEquals(2, stations.size(), "entry without macAddress is skipped");
    Station s = stations.get(0);
    assertEquals("00:0e:c6:20:0f:7b", s.macAddress());
    assertEquals("00-0E-C6-20-0F-7B", s.nodeKey());
    assertEquals("My Weather Station", s.displayName());
    assertEquals("Home", s.location());
    assertEquals((Object) 66.9, s.lastData().get("tempf"));
    assertEquals(30L, s.lastData().get("humidity"));
    assertEquals(Instant.parse("2018-01-08T18:35:00Z"), s.readingTime().orElseThrow());

    Station s2 = stations.get(1);
    assertEquals("Shop_Roof _north_", s2.displayName(), "tag-path characters replaced");
    assertEquals("", s2.location());
  }

  @Test
  void sendsBothKeysUrlEncoded() throws Exception {
    client().fetchStations();
    String q = lastQuery.get();
    assertTrue(q.contains("applicationKey=app+key%261"), q);
    assertTrue(q.contains("apiKey=api-key-secret"), q);
  }

  @Test
  void rateLimitIsReported() {
    status = 429;
    body = "{\"error\":\"above-user-rate-limit\"}";
    AmbientApiException e = assertThrows(AmbientApiException.class, () -> client().fetchStations());
    assertTrue(e.isRateLimited());
    assertFalse(e.isAuthError());
    assertTrue(e.getMessage().contains("429"));
  }

  @Test
  void authErrorNeverLeaksKeys() {
    status = 401;
    body = "{\"error\":\"apiKey-invalid\",\"apiKey\":\"api-key-secret\"}";
    AmbientApiException e = assertThrows(AmbientApiException.class, () -> client().fetchStations());
    assertTrue(e.isAuthError());
    assertFalse(e.getMessage().contains("api-key-secret"), e.getMessage());
    assertTrue(e.getMessage().contains("***"));
  }

  @Test
  void malformedBodyIsAnApiException() {
    body = "<html>maintenance</html>";
    AmbientApiException e = assertThrows(AmbientApiException.class, () -> client().fetchStations());
    assertEquals(-1, e.statusCode());
    assertTrue(e.getMessage().startsWith("Unexpected response"));
  }

  @Test
  void connectionFailureIsAnApiException() {
    server.stop(0);
    AmbientApiException e = assertThrows(AmbientApiException.class, () -> client().fetchStations());
    assertEquals(-1, e.statusCode());
    assertFalse(e.getMessage().contains("api-key-secret"));
  }

  @Test
  void blankKeysRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new AmbientWeatherClient(null, " ", "k", Duration.ofSeconds(1)));
  }
}
