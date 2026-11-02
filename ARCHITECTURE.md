# Sajda architecture (v2.3.0)

## Components

| Type | Role |
|------|------|
| `MainActivity` | Permissions, dialogs, pill, renderSession, a11y announcements |
| `LocationController` | Multi-provider search; per-provider try; total failure cleans listeners |
| `LocationQuality` | Coordinate + age (unit-tested) |
| `RequestGate` | generation + locating (unit-tested) |
| `SessionRepository` | Session + `LocationCache`; `clearHeadingTrust` |
| `LocationStore` | `LocationCache` implementation (`override` save/load/clear) |
| `HeadingPolicy` | Alignment / turn / confidence gates |
| `CompassView` | Dial + `clearDeviceHeading` |

## Lifecycle

1. `onPause`: `stop(invalidate=true)`, `compass.stop()`, `clearHeadingTrust()`, `clearDeviceHeading()`, haptic reset
2. `onResume`: `compass.start()`, `renderSession()`, `ensureLocation()`

## Accessibility

Text views expose content descriptions; compass view is focusable for TalkBack.
