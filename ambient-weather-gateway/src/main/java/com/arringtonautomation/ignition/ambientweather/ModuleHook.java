package com.arringtonautomation.ignition.ambientweather;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import java.util.List;

public class ModuleHook extends AbstractDeviceModuleHook {

  @Override
  public void setup(GatewayContext context) {
    super.setup(context);
    BundleUtil.get().addBundle(AmbientWeatherDevice.class);
  }

  @Override
  public void startup(LicenseState activationState) {
    super.startup(activationState);
  }

  @Override
  public void shutdown() {
    super.shutdown();
    BundleUtil.get().removeBundle(AmbientWeatherDevice.class);
  }

  @Override
  protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
    return List.of(new AmbientWeatherDeviceExtensionPoint());
  }

  /** New in 8.3, so there are no pre-8.3 internal-database records to migrate. */
  @Override
  public List<IdbMigrationStrategy> getRecordMigrationStrategies() {
    return List.of();
  }

  /** Free module: runs without a license and never enters trial mode. */
  @Override
  public boolean isFreeModule() {
    return true;
  }

  /** Lets the module load on Ignition Maker Edition gateways (the SDK default is false). */
  @Override
  public boolean isMakerEditionCompatible() {
    return true;
  }
}
