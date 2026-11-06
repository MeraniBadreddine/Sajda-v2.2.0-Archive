# Sajda v2.5.0

Closes audit items from the v2.5.0 repair prompt (code + design only).

## Closed items
1. **P0 LocationCandidatePolicy** — recency: prefer newer fix when >60s fresher and accuracy ≤2×; within tie band prefer newer. Tests added.
2. **P0 City→GPS path** — LocationFlowState unit tests + instrumented chrome tests with GrantPermissionRule; late-fix rejection remains via RequestGate generation.
3. **P1 AtKaaba** — QiblaEngine.solveDetailed marks within 100 m; UI shows "You are near the Kaaba" instead of a meaningless 0° bearing. GeoMath bearing formula unchanged.
4. **P1 LocationTrust** — GOOD/WEAK/UNKNOWN from accuracy + provisional; HeadingPolicy.shouldShowPreciseGuidance requires GOOD location AND precise sensor.
5. **P1 CompassView.applyDim** — base alphas captured from Paint at first use; dim scales them; restore uses captured values (no hard-coded mismatch).
6. **P2 tick Paths** — major/mid/fine Paths built in rebuildStatic; onDraw uses drawPath.
7. **P2 PillController** — debounce on mode/text/action; system location enabled cached 1s in MainActivity.
8. **P2 LocationFlowState** — permission/city/GPS-upgrade state extracted from MainActivity (MainActivity still ~830 lines; full ≤600 split needs a dedicated LocationCoordinator follow-up).
9. **P2 small** — landscape compass contentDescription already present; SessionRepository initial confidence LOW; AccessibilityAnnouncer uses HeadingPolicy.isAligned.

## Version
- versionName 2.5.0
- versionCode 51

## Note
Gradle was not executed in the repair environment (no Android SDK / Gradle network). Run:
`./gradlew :app:testDebugUnitTest :app:assembleDebug`
and device checks listed in the prompt before release.
