# Sajda architecture (v2.3.5)

## Location pipeline
1. Failures → LocationFailure (permission / disabled / providers / timeout)
2. Last-known → provisional + stopSearch=false
3. Fresh provider fix → source from provider name + stop search
4. Manual city → MANUAL_CITY, survives restart

## Compass
HeadingSource from successful registration drives classify().

## Tests
Unit: GeoMath, HeadingPolicy, RequestGate, LocationQuality, LocationSourceMapping, SessionRepository
