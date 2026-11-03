# Sajda
**Version 2.3.5** · **versionCode 31** · Apache-2.0

## Reliability & Validation

- `lastSource` property fixed (clean build)
- Provisional last-known continues search for fresh fix
- `LocationFailure` unified (TIMEOUT included)
- Real provider to LocationSource (GPS / NETWORK / FUSED / LAST_KNOWN / MANUAL_CITY)
- Location quality checks horizontal accuracy (unknown OK; extreme rejected)
- Permission pre-sheet: Continue with a city
- Regression unit tests for quality, source mapping, session source
- Source-only archive (no app/build, no local.properties)

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
