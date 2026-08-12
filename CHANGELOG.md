# Changelog

All notable changes to Mhz_Localiser are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project aims to follow semantic-ish versioning tied to the FAP/APK version.

## [2.1] - 2026-08-13

### Added
- **Bluetooth LE transport.** The Flipper FAP now streams the same CSV telemetry over
  **both USB CDC and Bluetooth LE** simultaneously. The Android app can connect over either —
  fully wireless, no cable required.
- **Dual-transport picker** in the app drawer (USB / Bluetooth); the solver, map, and
  allocation lookup behave identically on either transport.
- **BLE device picker** that lists nearby Flippers (matched by serial-service UUID and by
  name, so custom-named units without the "Flipper" prefix are found). The chosen device
  address is remembered for one-tap reconnect.
- **On-device Bluetooth status** on the Flipper running screen: `BT:off` / `BT:adv` / `BT:ok`.
- **RSSI smoothing** — exponential moving-average filter on the live stream to damp
  single-sample multipath spikes before capture.
- **Selectable environment / path-loss exponent `n`** (open field / suburban / urban /
  dense / indoor, or a custom value) in the drawer.
- **Automatic outlier rejection** — leave-one-out residual check excludes likely-multipath
  captures before the final solve.
- **Live capture-geometry hint** warning when captures are too collinear for a good fix.
- **Session persistence** — captures and settings survive an app restart.
- Live allocation sync driven by the Flipper stream, auto-detected frequency, and a
  display-only allocation view.

### Fixed
- BLE reliability: force the LE transport during pairing, self-heal advertising after a
  stack restart, refresh Android's stale GATT service cache before discovery, and bind the
  correct data characteristic (TX / indicate rather than the flow-control notify char).
- Tab navigation: the live stream no longer forces the app back to the Allocation tab,
  so the Triangulator view stays put while data streams.

### Changed
- `application.fam` now requires the `bt` service; FAP version bumped to 2.1.
- README, protocol docs, and requirements updated for dual-transport operation.

## [2.0]

### Changed
- Flipper FAP is **manual-frequency only** — the preset menu (315 / 433 / 868 / 915 MHz)
  was removed and the app opens directly on the digit editor for faster startup.
- Smaller FAP binary (preset table and menu renderer eliminated).
- Android plugin auto-reconnects over USB if the link drops.
- Enriched README; tightened `.gitignore`.

## [1.0]

### Added
- Initial release: Sub-GHz RSSI logger FAP streaming CSV over USB CDC.
- Android triangulation app: live readout, GPS + RSSI capture, Nelder–Mead least-squares
  solver with RMS error, Leaflet map.
- Offline spectrum allocation lookup (~2,450 rows: ITU R1/R2/R3, USA federal + non-federal,
  per-country EU) bundled as `spectrum.csv`, with the Python `spectrum_scraper` tooling.

[2.1]: https://github.com/TFD-42/Mhz_Localiser/releases
[2.0]: https://github.com/TFD-42/Mhz_Localiser/releases
[1.0]: https://github.com/TFD-42/Mhz_Localiser/releases
