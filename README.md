<div align="center">

# Sajda
### Direction, with intention.

**Version 2.3.1** · **versionCode 26** · API 26–35

</div>

## Highlights (2.3.1)

- Live TalkBack announcements on turn / alignment (throttled)
- Offline city fallback **only after GPS timeout** (or city pill)
- Locales: English, Arabic, French, Urdu, Turkish, Bengali, Indonesian
- Espresso smoke tests for main chrome
- Calibration stays as **bottom red banner** (no modal)
- 2.2.x reliability: multi-provider location, RequestGate, clear heading on pause

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
# device/emulator UI smoke:
./gradlew :app:connectedDebugAndroidTest
```

Privacy: on-device location only · no ads · no trackers.

Sajda · independent open-source project.
