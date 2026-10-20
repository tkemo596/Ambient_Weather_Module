package com.arringtonautomation.ignition.ambientweather;

import com.arringtonautomation.ignition.ambientweather.api.AmbientApiException;
import com.arringtonautomation.ignition.ambientweather.api.AmbientWeatherClient;
import com.arringtonautomation.ignition.ambientweather.api.FieldCatalog;
import com.arringtonautomation.ignition.ambientweather.api.FieldValues;
import com.arringtonautomation.ignition.ambientweather.api.PollBackoff;
import com.arringtonautomation.ignition.ambientweather.api.Station;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One configured Ambient Weather account. Each poll calls {@code GET /v1/devices} and mirrors the
 * result into the address space:
 *
 * <pre>
 * [Device]
 *   _Diagnostics/  Status, LastPollTime, LastSuccessTime, LastError, StationCount
 *   Backyard/                    (station name; node ids use the MAC so renames don't break tags)
 *     _Info/       MacAddress, Name, Location, LastReadingTime
 *     tempf, humidity, windspeedmph, ...   (whatever fields this station reports)
 * </pre>
 *
 * Fields are discovered from the data, so new sensors appear as new tags on the next poll.
 * Quality: Good on a fresh reading; Uncertain when the station's reading is older than the
 * stale threshold; Bad_CommunicationError when the API call fails; Bad_NoCommunication when a
 * station disappears from the account. Values are timestamped with the station's reading time.
 */
public class AmbientWeatherDevice extends ManagedAddressSpaceWithLifecycle implements Device {

  private static final String DIAG = "_Diagnostics";
  private static final String INFO = "_Info";

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final DeviceContext context;
  private final AmbientWeatherDeviceConfig config;
  private final SubscriptionModel subscriptionModel;

  // Everything below is touched only from the poll thread (plus startup/shutdown).
  private ScheduledExecutorService executor;
  private AmbientWeatherClient client;
  private PollBackoff backoff;
  private UaFolderNode rootNode;

  private final Map<String, UaFolderNode> stationFolders = new HashMap<>();
  private final Map<String, String> stationBrowseNames = new HashMap<>(); // nodeKey -> browse name
  private final Map<String, FieldNode> fieldNodes = new HashMap<>(); // nodeKey/field -> node
  private final Map<String, UaVariableNode> plainNodes = new HashMap<>(); // diagnostics + info

  private volatile String status = "Starting";

  public AmbientWeatherDevice(DeviceContext context, AmbientWeatherDeviceConfig config) {
    super(context.getServer());
    this.context = context;
    this.config = config;

    subscriptionModel = new SubscriptionModel(context.getServer(), this);

    getLifecycleManager().addLifecycle(subscriptionModel);
    getLifecycleManager().addStartupTask(this::onStartup);
    getLifecycleManager().addShutdownTask(this::onShutdown);
  }

  @Override
  public String getStatus() {
    return status;
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------------------------

  private void onStartup() {
    rootNode = new UaFolderNode(
        getNodeContext(),
        context.nodeId(context.getName()),
        context.qualifiedName(String.format("[%s]", context.getName())),
        new LocalizedText(String.format("[%s]", context.getName())));
    getNodeManager().addNode(rootNode);
    rootNode.addReference(new Reference(
        rootNode.getNodeId(),
        NodeIds.Organizes,
        context.getRootNodeId().expanded(),
        Reference.Direction.INVERSE));

    UaFolderNode diag = folder(rootNode, DIAG, DIAG);
    plain(diag, DIAG + "/Status", "Status", OpcUaDataType.String, "Driver status");
    plain(diag, DIAG + "/LastPollTime", "LastPollTime", OpcUaDataType.DateTime,
        "Time of the last API call, successful or not");
    plain(diag, DIAG + "/LastSuccessTime", "LastSuccessTime", OpcUaDataType.DateTime,
        "Time of the last successful API call");
    plain(diag, DIAG + "/LastError", "LastError", OpcUaDataType.String,
        "Most recent error, empty after a successful poll");
    plain(diag, DIAG + "/StationCount", "StationCount", OpcUaDataType.Int32,
        "Stations returned by the last successful poll");

    // Re-attach any tags that were already subscribed to this device (e.g. after a restart).
    onDataItemsCreated(context.getSubscriptionModel().getDataItems(context.getName()));

    try {
      client = new AmbientWeatherClient(
          config.connection().baseUrl(),
          resolveSecret(config.connection().applicationKey(), "Application Key"),
          resolveSecret(config.connection().apiKey(), "API Key"),
          Duration.ofSeconds(config.connection().requestTimeoutSeconds()));
    } catch (Exception e) {
      setStatus("Faulted: " + e.getMessage());
      setPlain(DIAG + "/LastError", e.getMessage());
      logger.error("[{}] Not polling: {}", context.getName(), e.getMessage());
      return;
    }

    int interval = config.polling().pollIntervalSeconds();
    backoff = new PollBackoff(Duration.ofSeconds(interval));
    setStatus("Connecting");

    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "AmbientWeather-" + context.getName());
      t.setDaemon(true);
      return t;
    });
    // Fixed delay (not rate) so a slow API response can never stack requests up.
    executor.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.SECONDS);
  }

  private void onShutdown() {
    if (executor != null) {
      executor.shutdownNow();
      try {
        executor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    context.getSubscriptionModel()
        .getDataItems(context.getName())
        .forEach(item -> item.setQuality(new StatusCode(StatusCodes.Uncertain_LastUsableValue)));
  }

  private String resolveSecret(SecretConfig secretConfig, String label) throws Exception {
    if (secretConfig == null) {
      throw new IllegalStateException(label + " is not set");
    }
    try (Plaintext plaintext = Secret.create(context.getGatewayContext(), secretConfig).getPlaintext()) {
      String value = plaintext.getAsString(StandardCharsets.UTF_8);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(label + " is empty");
      }
      return value.trim();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Could not read " + label + " from its secret: " + e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Polling
  // ---------------------------------------------------------------------------------------------

  /** Never lets an exception escape: one would silently cancel the scheduled task. */
  private void pollSafely() {
    try {
      poll();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Throwable t) {
      logger.error("[{}] Unexpected error while polling", context.getName(), t);
      setStatus("Faulted: " + t.getClass().getSimpleName());
    }
  }

  private void poll() throws InterruptedException {
    Instant now = Instant.now();
    if (!backoff.shouldPoll(now)) {
      return;
    }
    setPlain(DIAG + "/LastPollTime", toDateTime(now));

    List<Station> stations;
    try {
      stations = client.fetchStations();
    } catch (AmbientApiException e) {
      Duration retryIn = backoff.onFailure(Instant.now(), e.isAuthError());
      String msg = e.getMessage();
      setStatus((e.isAuthError() ? "Faulted: " : "Retrying: ") + msg);
      setPlain(DIAG + "/LastError", msg);
      markAllFields(new StatusCode(StatusCodes.Bad_CommunicationError));
      // Log the first failure and every 10th after it, so an outage doesn't flood the log.
      int n = backoff.consecutiveFailures();
      if (n == 1 || n % 10 == 0) {
        logger.warn("[{}] Poll failed ({} in a row), next attempt in {}s: {}",
            context.getName(), n, retryIn.toSeconds(), msg);
      }
      return;
    }

    if (backoff.consecutiveFailures() > 0) {
      logger.info("[{}] Poll recovered after {} failure(s)", context.getName(), backoff.consecutiveFailures());
    }
    backoff.onSuccess();

    Set<String> seen = new HashSet<>();
    for (Station station : stations) {
      seen.add(station.nodeKey());
      updateStation(station, now);
    }

    // Stations no longer on the account keep their last values, flagged as not communicating.
    for (Map.Entry<String, FieldNode> e : fieldNodes.entrySet()) {
      String key = e.getKey().substring(0, e.getKey().indexOf('/'));
      if (!seen.contains(key)) {
        e.getValue().setQuality(new StatusCode(StatusCodes.Bad_NoCommunication));
      }
    }

    setPlain(DIAG + "/LastSuccessTime", toDateTime(Instant.now()));
    setPlain(DIAG + "/LastError", "");
    setPlain(DIAG + "/StationCount", stations.size());
    setStatus(stations.isEmpty()
        ? "Connected (no stations on this account)"
        : "Connected (" + stations.size() + (stations.size() == 1 ? " station)" : " stations)"));
  }

  private void updateStation(Station station, Instant now) {
    String key = station.nodeKey();
    UaFolderNode folder = stationFolders.computeIfAbsent(key, k -> createStationFolder(station));

    Instant readingTime = station.readingTime().orElse(now);
    DateTime sourceTime = toDateTime(readingTime);
    boolean stale = Duration.between(readingTime, now)
        .compareTo(Duration.ofMinutes(config.polling().staleAfterMinutes())) > 0;
    StatusCode quality = stale
        ? new StatusCode(StatusCodes.Uncertain_LastUsableValue)
        : StatusCode.GOOD;

    setPlain(key + "/" + INFO + "/Name", station.name());
    setPlain(key + "/" + INFO + "/Location", station.location());
    setPlain(key + "/" + INFO + "/LastReadingTime", sourceTime);

    for (Map.Entry<String, Object> field : station.lastData().entrySet()) {
      String fieldName = field.getKey();
      Object raw = field.getValue();
      String nodeKey = key + "/" + fieldName;

      FieldNode fn = fieldNodes.get(nodeKey);
      if (fn == null) {
        Optional<FieldValues.Kind> kind = FieldValues.kindOf(fieldName, raw);
        if (kind.isEmpty()) {
          continue; // null on first sight: create the tag once a real value shows up
        }
        fn = createFieldNode(folder, nodeKey, fieldName, kind.get());
        fieldNodes.put(nodeKey, fn);
      }

      Optional<Object> value = FieldValues.convert(fn.kind, raw);
      if (value.isPresent()) {
        fn.set(toVariant(value.get()), quality, sourceTime);
      } else {
        fn.setQuality(new StatusCode(StatusCodes.Bad_TypeMismatch));
      }
    }

    // A sensor that dropped out of this reading (e.g. a dead remote) is flagged, not deleted.
    String prefix = key + "/";
    for (Map.Entry<String, FieldNode> e : fieldNodes.entrySet()) {
      if (e.getKey().startsWith(prefix)
          && !station.lastData().containsKey(e.getKey().substring(prefix.length()))) {
        e.getValue().setQuality(new StatusCode(StatusCodes.Bad_NoData));
      }
    }
  }

  private UaFolderNode createStationFolder(Station station) {
    String key = station.nodeKey();

    // Two stations with the same name would collide in the tag browser; add the MAC to the second.
    String browse = station.displayName();
    if (stationBrowseNames.containsValue(browse)) {
      browse = browse + " (" + key + ")";
    }
    stationBrowseNames.put(key, browse);

    UaFolderNode folder = folder(rootNode, key, browse);
    UaFolderNode info = folder(folder, key + "/" + INFO, INFO);
    plain(info, key + "/" + INFO + "/MacAddress", "MacAddress", OpcUaDataType.String,
        "Station MAC address");
    plain(info, key + "/" + INFO + "/Name", "Name", OpcUaDataType.String,
        "Station name on AmbientWeather.net");
    plain(info, key + "/" + INFO + "/Location", "Location", OpcUaDataType.String,
        "Station location label on AmbientWeather.net");
    plain(info, key + "/" + INFO + "/LastReadingTime", "LastReadingTime", OpcUaDataType.DateTime,
        "When the station took its latest reading");
    setPlain(key + "/" + INFO + "/MacAddress", station.macAddress());

    logger.info("[{}] Found station '{}' ({})", context.getName(), browse, station.macAddress());
    return folder;
  }

  private FieldNode createFieldNode(
      UaFolderNode folder, String nodeKey, String fieldName, FieldValues.Kind kind) {

    OpcUaDataType type = switch (kind) {
      case NUMBER -> OpcUaDataType.Double;
      case TIMESTAMP -> OpcUaDataType.DateTime;
      case BOOLEAN -> OpcUaDataType.Boolean;
      case STRING -> OpcUaDataType.String;
    };
    String description = FieldCatalog.describe(fieldName).orElse(fieldName);
    UaVariableNode node = variable(folder, nodeKey, fieldName, type, description);
    return new FieldNode(node, kind);
  }

  // ---------------------------------------------------------------------------------------------
  // Node helpers
  // ---------------------------------------------------------------------------------------------

  private UaFolderNode folder(UaFolderNode parent, String idPath, String browseName) {
    UaFolderNode f = new UaFolderNode(
        getNodeContext(),
        context.nodeId(idPath),
        context.qualifiedName(browseName),
        new LocalizedText(browseName));
    getNodeManager().addNode(f);
    parent.addOrganizes(f);
    return f;
  }

  private UaVariableNode variable(
      UaFolderNode parent, String idPath, String browseName, OpcUaDataType type, String description) {

    NodeId nodeId = context.nodeId(idPath);
    UaVariableNode node = UaVariableNode.build(getNodeContext(), b ->
        b.setNodeId(nodeId)
            .setBrowseName(context.qualifiedName(browseName))
            .setDisplayName(new LocalizedText(browseName))
            .setDescription(new LocalizedText(description))
            .setDataType(type.getNodeId())
            .setTypeDefinition(NodeIds.BaseDataVariableType)
            .setAccessLevel(AccessLevel.READ_ONLY)
            .setUserAccessLevel(AccessLevel.READ_ONLY)
            .build());
    // Until the first poll lands, readers see "waiting for initial data" rather than a null Good.
    node.setValue(new DataValue(Variant.NULL_VALUE, new StatusCode(StatusCodes.Bad_WaitingForInitialData), null, DateTime.now()));
    getNodeManager().addNode(node);
    parent.addOrganizes(node);
    return node;
  }

  private void plain(UaFolderNode parent, String idPath, String browseName, OpcUaDataType type, String desc) {
    plainNodes.put(idPath, variable(parent, idPath, browseName, type, desc));
  }

  private void setPlain(String idPath, Object value) {
    UaVariableNode node = plainNodes.get(idPath);
    if (node != null) {
      node.setValue(new DataValue(new Variant(value), StatusCode.GOOD, DateTime.now(), DateTime.now()));
    }
  }

  private void setStatus(String s) {
    status = s;
    setPlain(DIAG + "/Status", s);
  }

  private void markAllFields(StatusCode quality) {
    fieldNodes.values().forEach(fn -> fn.setQuality(quality));
  }

  private static Variant toVariant(Object value) {
    if (value instanceof Instant i) {
      return new Variant(toDateTime(i));
    }
    return new Variant(value);
  }

  private static DateTime toDateTime(Instant instant) {
    return new DateTime(Date.from(instant));
  }

  /** A data field node plus the type it was created with, so later values are coerced to match. */
  private static final class FieldNode {
    final UaVariableNode node;
    final FieldValues.Kind kind;
    Variant lastValue = Variant.NULL_VALUE;
    DateTime lastSourceTime;

    FieldNode(UaVariableNode node, FieldValues.Kind kind) {
      this.node = node;
      this.kind = kind;
    }

    void set(Variant value, StatusCode quality, DateTime sourceTime) {
      lastValue = value;
      lastSourceTime = sourceTime;
      node.setValue(new DataValue(value, quality, sourceTime, DateTime.now()));
    }

    /** Keeps the last value and its timestamp, changing only quality. */
    void setQuality(StatusCode quality) {
      node.setValue(new DataValue(lastValue, quality, lastSourceTime, DateTime.now()));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Subscription plumbing (unchanged from the SDK example)
  // ---------------------------------------------------------------------------------------------

  @Override
  public void onDataItemsCreated(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsCreated(dataItems);
  }

  @Override
  public void onDataItemsModified(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsModified(dataItems);
  }

  @Override
  public void onDataItemsDeleted(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsDeleted(dataItems);
  }

  @Override
  public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
    subscriptionModel.onMonitoringModeChanged(monitoredItems);
  }
}
