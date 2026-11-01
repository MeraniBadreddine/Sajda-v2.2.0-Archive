<div align="center">

# Sajda
### Direction, with intention.

A refined Qibla compass for Android.  
Native sensors. Honest confidence. Minimal by design.

**Version 2.2.0** · **versionCode 22** · **API 26–35**

[Download](https://github.com/MeraniBadreddine/Sajda/releases)

</div>

---

## Architecture

```
MainActivity            UI shell
LocationController      GPS / NETWORK / FUSED (independent registration)
LocationQuality         Coordinate + age rules (unit-tested)
RequestGate             Late-callback rejection (unit-tested)
SessionRepository       QiblaSession + LocationCache
HeadingPolicy           Alignment / guidance (unit-tested)
CompassEngine           Sensors + confidence
CompassView             Dial; clearDeviceHeading on trust loss
```

## Build

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
# or ./gradlew if the wrapper is present
```

## Privacy

- Location on-device only · `allowBackup=false` · no cleartext traffic · no background location.

## Changelog

### 2.2.0
- Location providers registered independently; partial failure does not block others; total failure removes listeners.
- `RequestGate` + tests for late callbacks after stop.
- `clearDeviceHeading()` when trust is revoked on pause.
- `LocationCache` + real `SessionRepositoryTest` (provisional, clearHeadingTrust, persist).
- CI runs unit tests and `assembleDebug` without swallowing failures.

### 2.1.0
- FUSED null no longer aborts search; invalidate on pause; heading trust reset.

### 2.0.0
- Split MainActivity into controllers and policies.

---

Sajda · independent open-source project.
