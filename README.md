# Sajda 2.4.3

First-launch location hang fixes:
- getCurrentLocation on NETWORK + GPS (+ FUSED on S+), not FUSED alone
- requestLocationUpdates prefers NETWORK; 15s timeout
- LOCATING pill clickable → city picker
- After 8s without fix: "tap to choose city" message
- WindowInsets (2.4.2) retained

versionCode 43
