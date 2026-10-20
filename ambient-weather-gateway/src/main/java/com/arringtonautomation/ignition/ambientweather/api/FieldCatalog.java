package com.arringtonautomation.ignition.ambientweather.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable descriptions (with units) for known Ambient Weather fields, from
 * https://github.com/ambient-weather/api-docs/wiki/Device-Data-Specs. Used as the OPC node
 * Description so the meaning and units show up when browsing tags. Unknown fields still get a
 * tag; they just have no description.
 */
public final class FieldCatalog {

  private static final Map<String, String> EXACT = new LinkedHashMap<>();
  private static final Map<Pattern, String> NUMBERED = new LinkedHashMap<>();

  static {
    // Wind
    EXACT.put("winddir", "Wind direction, instantaneous (deg)");
    EXACT.put("windspeedmph", "Wind speed, instantaneous (mph)");
    EXACT.put("windgustmph", "Max wind gust, last 10 min (mph)");
    EXACT.put("maxdailygust", "Max wind gust, today (mph)");
    EXACT.put("windgustdir", "Wind direction at gust (deg)");
    EXACT.put("windspdmph_avg2m", "Wind speed, 2-min average (mph)");
    EXACT.put("winddir_avg2m", "Wind direction, 2-min average (deg)");
    EXACT.put("windspdmph_avg10m", "Wind speed, 10-min average (mph)");
    EXACT.put("winddir_avg10m", "Wind direction, 10-min average (deg)");

    // Temperature & humidity
    EXACT.put("tempf", "Outdoor temperature (degF)");
    EXACT.put("humidity", "Outdoor humidity (%)");
    EXACT.put("tempinf", "Indoor temperature (degF)");
    EXACT.put("humidityin", "Indoor humidity (%)");
    EXACT.put("dewPoint", "Outdoor dew point (degF)");
    EXACT.put("feelsLike", "Outdoor feels-like: wind chill below 50F, heat index above 68F (degF)");
    EXACT.put("dewPointin", "Indoor dew point (degF)");
    EXACT.put("feelsLikein", "Indoor feels-like (degF)");

    // Precipitation & pressure
    EXACT.put("hourlyrainin", "Rain rate, hourly (in/hr)");
    EXACT.put("dailyrainin", "Rain, today (in)");
    EXACT.put("24hourrainin", "Rain, last 24 hours (in)");
    EXACT.put("weeklyrainin", "Rain, this week (in)");
    EXACT.put("monthlyrainin", "Rain, this month (in)");
    EXACT.put("yearlyrainin", "Rain, this year (in)");
    EXACT.put("eventrainin", "Rain, current event (in)");
    EXACT.put("totalrainin", "Rain, total since reset (in)");
    EXACT.put("lastRain", "Last time rain was recorded");
    EXACT.put("baromrelin", "Barometric pressure, relative (inHg)");
    EXACT.put("baromabsin", "Barometric pressure, absolute (inHg)");

    // Solar, air quality
    EXACT.put("uv", "UV index");
    EXACT.put("solarradiation", "Solar radiation (W/m2)");
    EXACT.put("pm25", "PM2.5, outdoor (ug/m3)");
    EXACT.put("pm25_24h", "PM2.5, outdoor 24-hr average (ug/m3)");
    EXACT.put("pm25_in", "PM2.5, indoor (ug/m3)");
    EXACT.put("pm25_in_24h", "PM2.5, indoor 24-hr average (ug/m3)");
    EXACT.put("pm25_in_aqin", "PM2.5, indoor AQIN (ug/m3)");
    EXACT.put("pm25_in_24h_aqin", "PM2.5, indoor AQIN 24-hr average (ug/m3)");
    EXACT.put("pm10_in_aqin", "PM10, indoor AQIN (ug/m3)");
    EXACT.put("pm10_in_24h_aqin", "PM10, indoor AQIN 24-hr average (ug/m3)");
    EXACT.put("co2", "CO2 (ppm)");
    EXACT.put("co2_in_aqin", "CO2, indoor AQIN (ppm)");
    EXACT.put("co2_in_24h_aqin", "CO2, indoor AQIN 24-hr average (ppm)");
    EXACT.put("pm_in_temp_aqin", "AQIN sensor temperature (degF)");
    EXACT.put("pm_in_humidity_aqin", "AQIN sensor humidity (%)");
    EXACT.put("aqi_pm25_aqin", "AQI from PM2.5, AQIN");
    EXACT.put("aqi_pm25_24h_aqin", "AQI from PM2.5, AQIN 24-hr");
    EXACT.put("aqi_pm10_aqin", "AQI from PM10, AQIN");
    EXACT.put("aqi_pm10_24h_aqin", "AQI from PM10, AQIN 24-hr");
    EXACT.put("aqi_pm25_in", "AQI from PM2.5, indoor");
    EXACT.put("aqi_pm25_in_24h", "AQI from PM2.5, indoor 24-hr");

    // Agricultural
    EXACT.put("gdd", "Growing degree days");
    EXACT.put("etos", "Evapotranspiration, short reference (in/day)");
    EXACT.put("etrs", "Evapotranspiration, tall reference (in/day)");

    // Lightning
    EXACT.put("lightning_day", "Lightning strikes, today");
    EXACT.put("lightning_hour", "Lightning strikes, last hour");
    EXACT.put("lightning_time", "Last lightning strike time");
    EXACT.put("lightning_distance", "Last lightning strike distance (mi)");

    // Batteries (standard stations: 1 = OK, 0 = low; Meteobridge inverts this)
    String batt = " battery (1 = OK, 0 = low; inverted on Meteobridge)";
    EXACT.put("battout", "Outdoor sensor" + batt);
    EXACT.put("battin", "Indoor sensor" + batt);
    EXACT.put("batt_25", "PM2.5 sensor" + batt);
    EXACT.put("batt_lightning", "Lightning detector" + batt);
    EXACT.put("batt_co2", "CO2 sensor" + batt);
    EXACT.put("batt_cellgateway", "Cellular gateway" + batt);

    // System
    EXACT.put("dateutc", "Time of this reading (UTC)");
    EXACT.put("date", "Time of this reading");
    EXACT.put("tz", "Station time zone (IANA)");

    // Numbered sensor families: {n} is replaced with the channel number.
    NUMBERED.put(Pattern.compile("temp(\\d+)f"), "Sensor {n} temperature (degF)");
    NUMBERED.put(Pattern.compile("humidity(\\d+)"), "Sensor {n} humidity (%)");
    NUMBERED.put(Pattern.compile("feelsLike(\\d+)"), "Sensor {n} feels-like (degF)");
    NUMBERED.put(Pattern.compile("dewPoint(\\d+)"), "Sensor {n} dew point (degF)");
    NUMBERED.put(Pattern.compile("soiltemp(\\d+)f"), "Soil {n} temperature (degF)");
    NUMBERED.put(Pattern.compile("soilhum(\\d+)"), "Soil {n} moisture (%)");
    NUMBERED.put(Pattern.compile("soiltens(\\d+)"), "Soil {n} tension (cb)");
    NUMBERED.put(Pattern.compile("leafwetness(\\d+)"), "Leaf wetness {n} (%)");
    NUMBERED.put(Pattern.compile("relay(\\d+)"), "Relay {n} state (0/1)");
    NUMBERED.put(Pattern.compile("leak(\\d+)"), "Leak detector {n} (0 = OK, 1 = leak, 2 = offline)");
    NUMBERED.put(Pattern.compile("batt(\\d+)"), "Sensor {n}" + batt);
    NUMBERED.put(Pattern.compile("batleak(\\d+)"), "Leak detector {n}" + batt);
    NUMBERED.put(Pattern.compile("battsm(\\d+)"), "Soil moisture sensor {n}" + batt);
  }

  private FieldCatalog() {}

  public static Optional<String> describe(String field) {
    String exact = EXACT.get(field);
    if (exact != null) {
      return Optional.of(exact);
    }
    for (Map.Entry<Pattern, String> e : NUMBERED.entrySet()) {
      Matcher m = e.getKey().matcher(field);
      if (m.matches()) {
        return Optional.of(e.getValue().replace("{n}", m.group(1)));
      }
    }
    return Optional.empty();
  }
}
