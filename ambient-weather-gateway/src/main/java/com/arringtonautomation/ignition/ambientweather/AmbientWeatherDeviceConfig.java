package com.arringtonautomation.ignition.ambientweather;

import com.arringtonautomation.ignition.ambientweather.api.AmbientWeatherClient;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.*;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Device settings shown on the gateway's "Device Connection" form. Both keys are SECRET fields,
 * so they're stored encrypted (or referenced from a secret provider) instead of in plain text.
 */
public record AmbientWeatherDeviceConfig(Connection connection, Polling polling) {

  public static final int DEFAULT_POLL_SECONDS = 60;
  public static final int DEFAULT_TIMEOUT_SECONDS = 10;
  public static final int DEFAULT_STALE_MINUTES = 10;

  public AmbientWeatherDeviceConfig {
    if (connection == null) {
      connection = new Connection(null, null, null, null);
    }
    if (polling == null) {
      polling = new Polling(null, null);
    }
  }

  public record Connection(
      @FormCategory("CONNECTION")
          @Label("Application Key*")
          @FormField(FormFieldType.SECRET)
          @Required
          @Description("Identifies this application. Create one on your AmbientWeather.net account page.")
          SecretConfig applicationKey,

      @FormCategory("CONNECTION")
          @Label("API Key*")
          @FormField(FormFieldType.SECRET)
          @Required
          @Description("Grants read access to the stations on your AmbientWeather.net account.")
          SecretConfig apiKey,

      @FormCategory("CONNECTION")
          @Label("API Base URL")
          @FormField(FormFieldType.TEXT)
          @DefaultValue(AmbientWeatherClient.DEFAULT_BASE_URL)
          @Description("REST endpoint. Leave at the default unless Ambient Weather changes it.")
          String baseUrl,

      @FormCategory("CONNECTION")
          @Label("Request Timeout (s)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("10")
          @Minimum("1")
          @Maximum("120")
          @NonSecret
          @Description("How long to wait for the API before marking the poll failed.")
          Integer requestTimeoutSeconds) {

    public Connection {
      if (baseUrl == null || baseUrl.isBlank()) {
        baseUrl = AmbientWeatherClient.DEFAULT_BASE_URL;
      }
      if (requestTimeoutSeconds == null) {
        requestTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
      }
    }
  }

  public record Polling(
      @FormCategory("POLLING")
          @Label("Poll Interval (s)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("60")
          @Minimum("10")
          @NonSecret
          @Description("How often to fetch the latest readings. Stations typically report every 1-5 minutes.")
          Integer pollIntervalSeconds,

      @FormCategory("POLLING")
          @Label("Stale After (min)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("10")
          @Minimum("1")
          @NonSecret
          @Description("If a station's latest reading is older than this, its tags go to Uncertain quality.")
          Integer staleAfterMinutes) {

    public Polling {
      if (pollIntervalSeconds == null) {
        pollIntervalSeconds = DEFAULT_POLL_SECONDS;
      }
      if (staleAfterMinutes == null) {
        staleAfterMinutes = DEFAULT_STALE_MINUTES;
      }
    }
  }
}
