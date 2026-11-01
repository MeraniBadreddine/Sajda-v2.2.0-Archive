# Sajda architecture (v2.2)

## Location search

1. Usable last-known (any provider)
2. Optional `getCurrentLocation(FUSED)` — null does not end search
3. `requestLocationUpdates` per provider in isolation (GPS, NETWORK, FUSED)
4. If **zero** providers register → remove listeners → `onLocationUnavailable`
5. Timeout 25s → `onLocationTimeout`

Acceptance: `RequestGate.isActive(id, generation, locating)` before any fix delivery.

## Sensor lifecycle

| Event | Action |
|-------|--------|
| `onPause` | `stop(invalidate=true)`, `compass.stop()`, `clearHeadingTrust()`, `clearDeviceHeading()`, haptic reset |
| `onResume` | `compass.start()`, `renderSession()`, `ensureLocation()` |

## Tests

- GeoMath, HeadingPolicy, LocationQuality, RequestGate, SessionRepository, HeadingFilter
