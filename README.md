# Ambient Weather Driver for Ignition 8.3

An OPC UA device driver module that polls the [Ambient Weather REST API](https://ambientweather.docs.apiary.io/)
and exposes every station on an AmbientWeather.net account as browsable OPC tags. Built on the
Ignition 8.3 `opc-ua-device` SDK example pattern.

## What you get

Add a device of type **Ambient Weather** under *Connections → Devices*, and browse:

```
[Weather]
  _Diagnostics/   Status, LastPollTime, LastSuccessTime, LastError, StationCount
  Backyard/                       <- station name (node ids use the MAC, so renaming is safe)
    _Info/        MacAddress, Name, Location, LastReadingTime
    tempf, humidity, windspeedmph, baromrelin, dailyrainin, ...
```

- **Tags are discovered from the data.** Whatever fields a station reports become tags; add a soil
  probe or a PM2.5 sensor and its tags appear on the next poll. No tag list to maintain.
- **Types:** every reading is a Double (the API sends `humidity` as `30` one minute and `30.5`
  the next); `dateutc`, `date`, `lastRain`, `lightning_time` are DateTime; `tz` is String.
- **Descriptions with units** on known fields (e.g. `tempf` → "Outdoor temperature (degF)"), from
  Ambient's [Device Data Specs](https://github.com/ambient-weather/api-docs/wiki/Device-Data-Specs).
- **Timestamps** are the station's reading time (`dateutc`), not the poll time.

### Quality

| Situation | Quality |
|---|---|
| Fresh reading | Good |
| Station's latest reading older than *Stale After* | Uncertain_LastUsableValue |
| API call failed (network, 5xx, 429, bad keys) | Bad_CommunicationError (last value kept) |
| A sensor dropped out of the station's reading | Bad_NoData |
| Station removed from the account | Bad_NoCommunication |
| Before the first successful poll | Bad_WaitingForInitialData |

## Settings

| Field | Default | Notes |
|---|---|---|
| Application Key | – | Secret field (encrypted, or a secret-provider reference). Create on your AmbientWeather.net account page. |
| API Key | – | Secret field. Same page. |
| API Base URL | `https://rt.ambientweather.net/v1` | Leave as is. |
| Request Timeout (s) | 10 | 1–120 |
| Poll Interval (s) | 60 | Minimum 10. Stations report every 1–5 min, so 60 s is plenty. |
| Stale After (min) | 10 | |

### Rate limits and backoff

Ambient allows 1 request/s per API key and 3/s per application key. Each poll is one
`GET /v1/devices` call regardless of station count. After a failure the driver backs off
(interval × 2, × 4, … capped at 5 min); invalid keys go straight to the 5-minute cap. The first
failure and every 10th consecutive one are logged; the keys are never written to logs or status
text (they travel as query parameters, so error messages are scrubbed).

## Building

Requires JDK 17+ and Maven, with access to Inductive Automation's Nexus repository.

```
mvn package
```

The unsigned `.modl` is written to `ambient-weather-build/target/`.
Install it from *Platform → Modules*. It's unsigned by default: either sign it with IA's
[module-signer](https://github.com/inductiveautomation/module-signer) or run the dev gateway with
unsigned modules allowed.

The vendor shown on the Modules page is **Arrington Automation**, set by the `module-vendor`
property in the root `pom.xml`. `ignition-maven-plugin` has no vendor setting, so a
`maven-antrun-plugin` step in `ambient-weather-build/pom.xml` adds `<vendorName>` to the finished
`.modl`'s `module.xml`; look for `module.xml: vendorName set to ...` in the build output. Once the
module is signed, 8.3 takes the vendor from the certificate CN instead.

Run the unit tests alone with `mvn -pl ambient-weather-gateway test`.

## Project layout

```
ambient-weather-gateway/src/main/java/com/arringtonautomation/ignition/ambientweather/
  ModuleHook.java                         registers the device type (free module, no migrations)
  AmbientWeatherDeviceExtensionPoint.java device type + config form + validation
  AmbientWeatherDeviceConfig.java         settings record (SECRET fields for both keys)
  AmbientWeatherDevice.java               poll loop, node creation, quality handling
  api/                                    no Ignition dependencies; unit-tested on its own
    AmbientWeatherClient.java             java.net.http client for GET /v1/devices
    Station.java, FieldValues.java, FieldCatalog.java, PollBackoff.java, MiniJson.java
ambient-weather-build/                    .modl packaging (ignition-maven-plugin)
```

The `api` package deliberately avoids Ignition's shaded Gson and other SDK classes, so it compiles
and tests without the SDK on the classpath.

## Not included (yet)

- **Realtime websocket** (Socket.IO at `rt2.ambientweather.net`): polling was chosen to avoid a
  Socket.IO client dependency. The device class is structured so a push source could feed
  `updateStation()` later.
- **History backfill** from `GET /v1/devices/{mac}` (up to 288 records). Use the Tag Historian on
  the polled tags instead, or add a script function later.
- **Engineering-unit conversion** (°C, km/h, hPa): values are imperial as Ambient sends them; use
  tag scaling or expression tags.

## License

Copyright 2026 Arrington Automation Incorporated. Licensed under the
[Apache License 2.0](LICENSE); see [NOTICE](NOTICE) for attributions. Ignition is a trademark of
Inductive Automation, and this is an independent module, not an Inductive Automation product.
