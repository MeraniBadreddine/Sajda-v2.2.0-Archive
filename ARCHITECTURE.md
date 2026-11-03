# Sajda architecture (v2.3.7)

## Compass
`CompassEngine.remapToDisplay()` maps rotation-vector / accel-mag matrices to the current `Display.rotation` before `getOrientation`.

## Location
`LocationFailure`, provisional+continue search, `LocationSource` persisted, COARSE by default.

## Trust UI
Permission state machine; no false calibrate without sensor frames; integer degrees; align hysteresis 3°/6°.
