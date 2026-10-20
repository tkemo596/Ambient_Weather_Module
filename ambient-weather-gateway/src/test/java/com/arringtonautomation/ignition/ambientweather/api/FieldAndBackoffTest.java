package com.arringtonautomation.ignition.ambientweather.api;

import static org.junit.jupiter.api.Assertions.*;

import com.arringtonautomation.ignition.ambientweather.api.FieldValues.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FieldAndBackoffTest {

  @Test
  void kinds() {
    assertEquals(Optional.of(Kind.NUMBER), FieldValues.kindOf("tempf", 66.9));
    assertEquals(Optional.of(Kind.NUMBER), FieldValues.kindOf("humidity", 30L));
    assertEquals(Optional.of(Kind.TIMESTAMP), FieldValues.kindOf("dateutc", 1515436500000L));
    assertEquals(Optional.of(Kind.TIMESTAMP), FieldValues.kindOf("lastRain", "2018-01-02T11:23:00.000Z"));
    assertEquals(Optional.of(Kind.STRING), FieldValues.kindOf("tz", "America/Denver"));
    assertEquals(Optional.of(Kind.BOOLEAN), FieldValues.kindOf("x", true));
    assertEquals(Optional.empty(), FieldValues.kindOf("tempf", null));
  }

  @Test
  void integerThenFractionalStaysDouble() {
    assertEquals(Optional.of(30.0), FieldValues.convert(Kind.NUMBER, 30L));
    assertEquals(Optional.of(30.5), FieldValues.convert(Kind.NUMBER, 30.5));
    assertEquals(Optional.of(12.0), FieldValues.convert(Kind.NUMBER, "12"));
    assertEquals(Optional.empty(), FieldValues.convert(Kind.NUMBER, "n/a"));
    assertEquals(Optional.empty(), FieldValues.convert(Kind.NUMBER, null));
  }

  @Test
  void timestamps() {
    assertEquals(Optional.of(Instant.parse("2018-01-08T18:35:00Z")),
        FieldValues.convert(Kind.TIMESTAMP, 1515436500000L));
    assertEquals(Optional.of(Instant.parse("2018-01-08T18:35:00Z")),
        FieldValues.convert(Kind.TIMESTAMP, 1515436500L), "epoch seconds detected");
    assertEquals(Optional.of(Instant.parse("2018-01-08T18:35:00Z")),
        FieldValues.convert(Kind.TIMESTAMP, "2018-01-08T11:35:00-07:00"));
  }

  @Test
  void catalog() {
    assertEquals("Outdoor temperature (degF)", FieldCatalog.describe("tempf").orElseThrow());
    assertEquals("Sensor 3 temperature (degF)", FieldCatalog.describe("temp3f").orElseThrow());
    assertEquals("Soil 2 moisture (%)", FieldCatalog.describe("soilhum2").orElseThrow());
    assertTrue(FieldCatalog.describe("batt4").orElseThrow().startsWith("Sensor 4 battery"));
    assertEquals(Optional.empty(), FieldCatalog.describe("somethingNew"));
  }

  @Test
  void backoffDoublesAndCaps() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    PollBackoff b = new PollBackoff(Duration.ofSeconds(60));
    assertTrue(b.shouldPoll(t));

    assertEquals(Duration.ofSeconds(120), b.onFailure(t, false));
    assertFalse(b.shouldPoll(t.plusSeconds(60)));
    assertTrue(b.shouldPoll(t.plusSeconds(120)));
    assertEquals(Duration.ofSeconds(240), b.onFailure(t, false));
    assertEquals(PollBackoff.MAX_DELAY, b.onFailure(t, false), "480s capped to 300s");
    assertEquals(3, b.consecutiveFailures());

    b.onSuccess();
    assertTrue(b.shouldPoll(t));
    assertEquals(0, b.consecutiveFailures());

    assertEquals(PollBackoff.MAX_DELAY, b.onFailure(t, true), "bad keys go straight to the cap");
  }

  @Test
  void backoffNeverShorterThanIntervalWhenIntervalExceedsCap() {
    PollBackoff b = new PollBackoff(Duration.ofMinutes(10));
    assertEquals(Duration.ofMinutes(10), b.onFailure(Instant.EPOCH, false));
  }

  @Test
  void jsonEdgeCases() {
    Object v = MiniJson.parse("{\"a\":[1,-2.5e2,\"x\\u00b0\\n\",null,true],\"b\":{}}");
    Map<?, ?> m = (Map<?, ?>) v;
    List<?> a = (List<?>) m.get("a");
    assertEquals(1L, a.get(0));
    assertEquals(-250.0, a.get(1));
    assertEquals("x°\n", a.get(2));
    assertNull(a.get(3));
    assertEquals(true, a.get(4));
    assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1,2"));
    assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{} x"));
  }
}
