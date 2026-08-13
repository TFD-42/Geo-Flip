# Contributing

Thanks for your interest in improving Mhz_Localise. Contributions of all kinds are welcome — bug reports, field-test results, code, documentation.

## Field data is especially valuable

Because RSSI-based localisation is fragile and environment-dependent, real-world capture sets are useful even when they fail. If you run the system, please consider opening an issue with:

- Hardware (Flipper firmware version, Android phone model)
- Frequency tested and approximate Tx power
- Environment (open field, suburban, urban, indoor)
- Number and rough geometry of captures
- Reported RMS error vs. the real distance you measured

This lets us calibrate the path-loss exponent guidance and document failure modes more honestly.

## Code

- **Flipper FAP** (`flipper/`) — keep dependencies to `furi`, `furi_hal`, `gui`, `storage`, `notification`, `bt`. No external libraries.
- **Android plugins** (`android/plugin/`) — `FlipperSerialPlugin` (USB, via `usb-serial-for-android`) and `FlipperBlePlugin` (Bluetooth, via the `android.bluetooth` framework — no extra dep). Both expose the same `connect`/`disconnect`/`data`/`status` interface; keep them interchangeable. Avoid adding heavyweight deps.
- **Web UI** (`android/www/`) — vanilla JS + Leaflet. No build step, no framework.

Run `clang-format` on C files and stick to 4-space indent / 100-col width.

## Pull requests

1. Open an issue first for non-trivial changes so we can discuss scope.
2. Keep PRs focused — one feature or fix per PR.
3. Update `README.md` if you change user-facing behaviour, the CSV protocol, or the solver.
4. Test on real hardware when you can. Note in the PR what you tested and what you didn't.

## Reporting bugs

Use the **Bug report** issue template. Include Flipper firmware version, Android version, and a capture export (`.json` or `.csv`) if the bug is solver-related.

## Out of scope

- Direction-finding / antenna-array features — the project deliberately stays RSSI-only.
- Anything that requires modifying the CC1101 driver in Flipper firmware.
- Server-side or cloud sync.
