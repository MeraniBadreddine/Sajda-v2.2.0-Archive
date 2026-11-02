# Sajda
### Direction, with intention.

**Version 2.3.2** · Apache-2.0 · API 26–35

## 2.3.2

- Localizable `CompassHint` (no English-only sensor strings)
- LICENSE (Apache-2.0)
- Release minify + R8
- Portrait without swallowing configChanges
- Stale magnetic accuracy → CALIBRATE after 3s
- Log.w on critical catches
- CI requires `./gradlew` only
- Removed dead `SolarAlignment`
- Last-known location marked provisional
- City fallback warning string
- Compass labels follow font scale

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```
