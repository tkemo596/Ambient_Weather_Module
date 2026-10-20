package com.arringtonautomation.ignition.ambientweather;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import java.util.Optional;
import java.util.Set;

/** Registers the "Ambient Weather" device type with Ignition's OPC UA server. */
public class AmbientWeatherDeviceExtensionPoint extends DeviceExtensionPoint<AmbientWeatherDeviceConfig> {

  public static final String TYPE_ID = "arrington.AmbientWeatherDevice";

  public AmbientWeatherDeviceExtensionPoint() {
    super(
        TYPE_ID,
        "AmbientWeatherDevice.Meta.DisplayName",
        "AmbientWeatherDevice.Meta.Description",
        AmbientWeatherDeviceConfig.class);
  }

  @Override
  protected Device createDevice(
      DeviceContext context,
      DeviceProfileConfig profileConfig,
      AmbientWeatherDeviceConfig deviceConfig) {

    return new AmbientWeatherDevice(context, deviceConfig);
  }

  @Override
  public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
    return Optional.of(
        new ExtensionPointResourceForm(
            DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
            "Device Connection",
            TYPE_ID,
            SchemaUtil.fromType(DeviceProfileConfig.class),
            SchemaUtil.fromType(AmbientWeatherDeviceConfig.class),
            Set.of()));
  }

  @Override
  protected void validate(AmbientWeatherDeviceConfig config, Builder errors) {
    var c = config.connection();
    var p = config.polling();

    errors.check(c.applicationKey() != null, "Application Key is required");
    errors.check(c.apiKey() != null, "API Key is required");
    errors.check(
        c.baseUrl().startsWith("https://") || c.baseUrl().startsWith("http://"),
        "API Base URL must start with https://");
    errors.check(
        c.requestTimeoutSeconds() >= 1 && c.requestTimeoutSeconds() <= 120,
        "Request Timeout must be between 1 and 120 seconds");
    // The API allows 1 request/s per API key; 10 s leaves room for several devices sharing a key.
    errors.check(p.pollIntervalSeconds() >= 10, "Poll Interval must be at least 10 seconds");
    errors.check(p.staleAfterMinutes() >= 1, "Stale After must be at least 1 minute");
  }
}
