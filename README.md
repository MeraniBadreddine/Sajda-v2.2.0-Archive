# Sajda

**Version: see `app/build.gradle`** (`versionName` / `versionCode`).

Offline Qibla compass for Android. No internet permission.

## Build
```bash
python3 scripts/check_string_parity.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

## Notes
- City fallback never loops on GPS permission pills
- CI builds debug + release (R8)
