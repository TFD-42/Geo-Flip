# android — Capacitor sources for RF Triangulator

This is the project-specific Capacitor content: the web UI in `www/` and the native
Java plugins in `plugin/`. The full Android Studio project structure (gradle files,
AndroidManifest.xml, MainActivity.java, etc.) is regenerated from `package.json` +
`npx cap add android` — see top-level `SETUP.md`.

## Files

```
www/
├── index.html       App shell with two tabs (Triangulator, Allocation List)
├── app.js           Transport bridge (USB/BLE), map, capture, Nelder-Mead solver,
│                    RSSI smoothing, path-loss n, outlier rejection, allocation lookup
├── styles.css       Dark theme, mobile-first
└── spectrum.csv     ~2 450 spectrum allocations (offline lookup table)

plugin/
├── FlipperSerialPlugin.java   Capacitor plugin: USB host (CDC-ACM), CSV line framing
└── FlipperBlePlugin.java      Capacitor plugin: Bluetooth LE (GATT serial), CSV line framing
```

## Build

See top-level `SETUP.md` for the full Capacitor bootstrap. Once the project is
scaffolded:

```bash
npx cap sync android
cd android && ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## www/ — UI

### Triangulator tab (default)
- Leaflet map fullscreen
- Top bar mirrors live Flipper readout: frequency + RSSI (smoothed) + signal bar
- Bottom bar: **Capture here**, **Auto-capture**, **Solve**
- Drawer (☰): **transport picker (USB / Bluetooth)**, connect/disconnect, import/export,
  environment / path-loss `n`, Tx power, outlier toggle, capture list, estimate readout

### Allocation List tab
Two modes selectable from a dropdown:

| Mode | Inputs | Behaviour |
|---|---|---|
| **List by Region / Country** | Country select, Region select | Live filter as you change selections |
| **List by MHz** | Text input (`433.92`, `2.4 GHz`, `88-108 MHz`, `868 kHz`) + Country select | Search on button click or Enter. Suggests nearest covered bands if the value falls in a gap |

Results columns: **MHz range / Country / Region / Service / Status / Application /
Source**. PRIMARY in green, Secondary in orange. The table tracks the live Flipper
frequency in the background (it no longer force-switches tabs). Limited to 500 rows
on screen; refine filters to see more.

## plugin/ — two interchangeable transports

Both plugins expose the **same JS API and event shapes**, so `app.js` treats USB and
BLE as drop-in replacements and everything downstream of line parsing is
transport-agnostic:

| Method / event | Purpose |
|---|---|
| `connect()` | USB: find the Flipper (VID `0x0483` / PID `0x5740`), request permission, open the 2nd CDC port. BLE: scan/pick the Flipper, pair, subscribe to the TX characteristic. |
| `disconnect()` | Close the port / GATT connection |
| `isConnected()` | Current connection state |
| `listDevices()` / `scan()` | Enumerate candidate Flippers (USB drivers / BLE advertisers) |
| `addListener('data', …)` | Emits `{ line }` per newline-terminated CSV row |
| `addListener('status', …)` | Emits `{ state: "connected" \| "disconnected" \| "error", message? }` |

Both share the same newline framing (`\n`-terminated, `\r` stripped), so a CSV line
split across USB chunks or BLE notify packets is reassembled identically.

### FlipperBlePlugin notes
- Uses only `android.bluetooth` framework APIs — **no extra Gradle dependency**.
- Targets the Flipper serial GATT service `8fe5b3d5-…-7acc60fe0000` and its **TX
  characteristic** `19ed82ae-…-228e61fe0000` (data arrives via **indications**, not
  notifications — the flow-control characteristic is the notify one and must not be
  chosen). UUIDs are little-endian on the wire (byte array reversed).
- Refreshes Android's cached GATT table before discovery, uses the API-33+
  `onCharacteristicChanged(…, byte[])` callback, forces the LE transport when pairing,
  and offers a device picker for custom-named Flippers (remembered for reconnect).

### Dependencies (`android/app/build.gradle`)
```gradle
dependencies {
    implementation 'com.github.mik3y:usb-serial-for-android:3.7.0' // USB only; BLE needs no dep
}
```
(and the JitPack repository in the root `build.gradle`).

### Register both plugins (`MainActivity.java`)
```java
@Override
public void onCreate(Bundle savedInstanceState) {
    registerPlugin(FlipperSerialPlugin.class);
    registerPlugin(FlipperBlePlugin.class);
    super.onCreate(savedInstanceState);
}
```

### Manifest permissions
USB host feature + location (GPS capture) + BLE (`BLUETOOTH_SCAN` flagged
`neverForLocation`, `BLUETOOTH_CONNECT`, and the pre-Android-12 fallbacks). See
`SETUP.md` for the exact block.

## License

MIT — see top-level LICENSE.
