# flipper — Flipper Zero FAP source

Source for `rf_logger.fap`, the Sub-GHz RSSI logger that runs on the Flipper Zero.
It samples the CC1101 RSSI at 5 Hz and streams it as CSV over **USB CDC and
Bluetooth LE simultaneously**, and can also log to the SD card.

## Build

With [ufbt](https://github.com/flipperdevices/flipperzero-ufbt):

```bash
ufbt              # build → dist/rf_logger.fap
ufbt launch       # build + flash + run on connected Flipper
```

Output: `dist/rf_logger.fap`. Copy to your Flipper SD card under `/ext/apps/Sub-GHz/`.

## Files

| File | Description |
|---|---|
| `rf_logger.c` | Application logic: state machine, viewport drawing, input handling, USB CDC + BLE serial streaming, SD logging, Sub-GHz tuning |
| `application.fam` | ufbt manifest: appid, name, category, icon, version, dependencies (`gui`, `subghz`, `bt`) |
| `rf_logger_icon.png` | 10x10 1-bit icon shown in the Apps menu |

## Architecture

The app is a **two-state machine** — there is no preset menu (removed in v2). It
opens directly on the manual frequency editor and BLE advertising starts at launch.

```
Launch ─► StateManualEntry          (XXX.XX MHz digit editor, default 433.92)
             │  BLE: profile started + advertising (self-healing keepalive)
             │
             └─[OK]──► StateRunning  (sample @ 5 Hz → USB CDC + BLE + optional SD)
                          │            status line: BT:off / BT:adv / BT:ok
                          └─[Back]──► StateManualEntry
```

## Manual MHz digit editor

Layout: `XXX.XX MHz` with a 1-pixel underline under the active digit.

| Input | Action |
|---|---|
| ↑ | Increment selected digit (0→1→…→9→0) |
| ↓ | Decrement selected digit |
| ← | Move cursor left |
| → | Move cursor right |
| OK | Start scanning at the displayed frequency |
| Back | Exit the app |

Range is clamped to **300.00 – 928.00 MHz** (CC1101 Sub-GHz). Digit changes are
independent — rolling a `9` up gives `0` of the same rank, never carries into the
neighbour digit (calculator-style, not number-spinner-style).

Cursor starts on the **ones-of-MHz** digit (position 2), initial value `433.92 MHz`.

In **StateRunning**: **OK** toggles SD-card logging on/off, **Back** returns to the
frequency editor.

## USB CDC

When entering StateRunning the app takes over the USB stack with `usb_cdc_dual`
(two CDC ACM interfaces). Interface 0 keeps the Flipper CLI live (so `qFlipper`
etc. keep working); interface 1 streams the CSV at 5 Hz. On exit, the original USB
config is restored.

## Bluetooth LE

At launch the app swaps the BLE stack to its own **Serial profile** instance
(`ble_profile_serial`, the same GATT serial service the stock CLI-over-BLE uses)
and starts advertising. Every sample line is also pushed to the connected central
via `ble_profile_serial_tx`, so the phone receives identical CSV whether it is
connected over USB or BLE.

- Advertising is **self-healing**: a keepalive re-advertises every ~2 s while not
  connected, and retries the profile swap if it failed at launch — a settings
  toggle, key wipe ("Forget All Paired Devices"), or stack restart no longer
  leaves the app silently un-advertised.
- The running screen shows the link state: `BT:off` / `BT:adv` / `BT:ok`.
- The default BLE profile is restored on exit.
- Requires **Settings → Bluetooth = ON** on the Flipper. Only one BLE central can
  hold the link, so keep the official Flipper mobile app closed while connected.

The `bt` service dependency is declared in `application.fam`.

## SD logging

When you press OK in StateRunning the app creates (or appends to) a CSV file:
```
/ext/apps_data/rf_logger/log_<tick>.csv
ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n
…
```

The directory is created on first use via `storage_simply_mkdir`.

## CSV format

```
ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n
```

`ts_ms` uptime · `req_hz`/`act_hz` requested vs. tuned frequency · `rssi_dbm`
signal · `rssi_raw` CC1101 register byte · `lqi` link quality · `n` sample counter
(resets on retune). Lines are terminated with `\r\n`; the same bytes go to USB, BLE,
and SD. Compiled binary: well within the ~64 KB FAP limit.

## License

MIT — see top-level LICENSE.
