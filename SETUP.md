# SETUP — build instructions

How to rebuild every artefact in `FINAL_MHZ/` from source.

## Prerequisites

| Component | Used for | Minimum version |
|---|---|---|
| `ufbt` (Flipper toolchain) | Build `rf_logger.fap` | latest from PyPI (`pipx install ufbt`) |
| Node.js + npm | Capacitor sync | Node 18+ |
| Java JDK | Android build | 17+ |
| Android SDK + Gradle | Android build | API 34, Gradle 8.x (Android Studio bundles these) |
| Python 3 | spectrum_scraper | 3.10+ (stdlib only, no pip needed) |

On macOS the quickest path is:
```bash
brew install --cask android-studio
brew install node openjdk@17
pipx install ufbt
```

## 1. Build the Flipper FAP

```bash
cd FINAL_MHZ/flipper
ufbt              # produces dist/rf_logger.fap
ufbt launch       # flashes it onto a connected Flipper and starts it
```

Output: `flipper/dist/rf_logger.fap`. Copy it to your Flipper SD card under `/ext/apps/Sub-GHz/`.

If you build inside the official Flipper firmware tree instead of with ufbt:
```bash
cp -r FINAL_MHZ/flipper flipperzero-firmware/applications_user/rf_logger
cd flipperzero-firmware
./fbt fap_rf_logger
# Output: build/f7-firmware-D/.extapps/rf_logger.fap
```

## 2. Build the Android APK

The Capacitor scaffolding is **not committed** because it is regenerated from `package.json` + `npx cap add android`. The repo only ships the parts that are unique to this project: `android/www/` and `android/plugin/FlipperSerialPlugin.java`.

To bootstrap a fresh Android project:

```bash
# 1) Create a Capacitor app shell
mkdir -p ~/build/rf-triangulator && cd ~/build/rf-triangulator
npm init -y
npm install --save @capacitor/core @capacitor/android @capacitor/geolocation
npm install --save-dev @capacitor/cli
npx cap init "RF Triangulator" com.mhzlocalise.rftriangulator --web-dir www

# 2) Drop in our web UI and native plugin
cp -r /path/to/FINAL_MHZ/android/www/* www/
mkdir -p android/app/src/main/java/com/mhzlocalise/rftriangulator/plugins
cp /path/to/FINAL_MHZ/android/plugin/FlipperSerialPlugin.java \
   android/app/src/main/java/com/mhzlocalise/rftriangulator/

# 3) Add the USB-serial dependency to android/app/build.gradle
#    implementation 'com.github.mik3y:usb-serial-for-android:3.7.0'
# Register the plugin in MainActivity.java:
#    registerPlugin(FlipperSerialPlugin.class);

# 4) Add the USB-host feature + filter to android/app/src/main/AndroidManifest.xml
#    (standard Capacitor USB-serial setup — see Capacitor docs)

# 5) Sync + build
npx cap add android         # if you haven't already
npx cap sync android
cd android && ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The build is reproducible up to the Gradle/Capacitor versions and the debug signing key. The web assets (`www/app.js`, `www/index.html`, `www/styles.css`, `www/spectrum.csv`) are bit-identical to those bundled in the published APK.

### USB Flipper IDs
The plugin filters for the Flipper Zero CDC-ACM device:
```
VID 0x0483    PID 0x5740
```
Permissions are requested at runtime via a `BroadcastReceiver`. No additional Android manifest entry is required beyond the standard Capacitor USB-host feature.

### BLE support (FlipperBlePlugin)

`android/plugin/FlipperBlePlugin.java` adds a Bluetooth Low Energy transport
alongside USB. It exposes the same JS API and event shapes as
`FlipperSerialPlugin`, so the web UI switches between them with a drawer
setting (Transport: USB cable / Bluetooth).

Build wiring (in addition to the steps above):

```java
// MainActivity.java
registerPlugin(FlipperBlePlugin.class);
```

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- pre-Android-12 BLE -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

No extra Gradle dependency — the plugin uses only `android.bluetooth`
framework APIs.

Runtime notes:
- Bluetooth must be enabled on the Flipper (**Settings → Bluetooth**).
- The Flipper's BLE Serial characteristics are encrypted; the first connect
  triggers Android's system pairing dialog. The plugin retries notification
  setup automatically once bonding completes.
- `connect()` with no arguments scans ~4 s and picks the strongest device
  advertising a `Flipper*` name; `connect({address})` targets a specific one.
- Auto-reconnect retries every 2 s after a link drop, matching the USB
  plugin's behavior.

**Current limitation — firmware side pending:** with stock firmware the BLE
Serial link carries the Flipper **CLI**, not the `rf_logger` CSV stream.
`rf_logger.c` currently writes only to USB CDC. Streaming the CSV over BLE
requires a firmware change (write the same sample lines through the BLE
serial profile), which must first be validated against the Flipper SDK with
`ufbt` — the BLE profile APIs available to third-party FAPs are not
guaranteed stable across firmware versions. Until that lands, the BLE
transport connects and streams whatever the serial service carries, but
triangulation data only flows over USB.

## 3. spectrum_scraper (mhz_allocator)

Pure stdlib — no `pip install` needed.

```bash
cd FINAL_MHZ/spectrum_scraper

# Interactive lookup (CLI)
python3 launcher.py
# [1] List all allocations, [2] Look up by MHz, [q] Quit

# Regenerate spectrum.csv from the baselines (offline, no EFIS)
python3 enrich_spectrum.py --skip-efis \
  --baseline data/baseline_USA_EU_ITU.csv \
  --out      output/spectrum_baseline_longform.csv
# Then append the EU per-country rows:
tail -n +2 data/eu_countries_longform.csv >> output/spectrum_baseline_longform.csv

# Copy back into the Android app
cp output/spectrum_baseline_longform.csv ../android/www/spectrum.csv
```

The EFIS (CEPT SOAP web service) enricher is included for completeness but the public endpoint has been moving recently and may return 404. Override it with `--efis-endpoint URL` if you have a working URL. SSL verification uses `certifi` if available, falls back to system CA store.

## 4. Verify the published binaries

After a clean rebuild, compare strings + ELF symbols (not bytes — timestamps differ between builds):

```bash
# Flipper FAP
nm --defined-only flipper/dist/rf_logger.fap > /tmp/rebuilt_syms.txt
nm --defined-only artifacts/rf_logger.fap   > /tmp/official_syms.txt
diff <(sort /tmp/rebuilt_syms.txt) <(sort /tmp/official_syms.txt)
# expected: empty (same code, only build timestamp differs)

strings -a flipper/dist/rf_logger.fap | sort -u > /tmp/rebuilt_strs.txt
strings -a artifacts/rf_logger.fap   | sort -u > /tmp/official_strs.txt
diff /tmp/rebuilt_strs.txt /tmp/official_strs.txt
# expected: empty
```

For the APK, compare `www/*` assets after `unzip`:

```bash
unzip -o artifacts/RF_Triangulator.apk -d /tmp/apk_official
md5 -r /tmp/apk_official/assets/public/{app.js,index.html,styles.css,spectrum.csv}
md5 -r android/www/{app.js,index.html,styles.css,spectrum.csv}
# expected: matching md5s
```

## USB serial protocol

The Flipper streams CSV over USB CDC-ACM at 115200 baud on the **second** CDC interface (index 1; interface 0 stays bound to the Flipper CLI). Format:

```
# RF_LOGGER_DBG req=433920000 act=433920000
ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n
1234567,433920000,433920000,-85,0x57,12,1
1234767,433920000,433920000,-83,0x59,14,2
```

| Column     | Type    | Description |
|------------|---------|-------------|
| `ts_ms`    | uint64  | Flipper uptime in milliseconds |
| `req_hz`   | uint32  | Requested frequency in Hz |
| `act_hz`   | uint32  | Actual tuned frequency in Hz |
| `rssi_dbm` | int     | Signal strength in dBm |
| `rssi_raw` | hex     | Raw CC1101 RSSI register byte |
| `lqi`      | uint8   | Link Quality Indicator |
| `n`        | uint32  | Sample counter, resets on retune |

Sample rate: 5 Hz (200 ms). The Android plugin parses the `req_hz` field to auto-detect the current frequency — no manual input needed on the phone side.

## License

MIT — see [LICENSE](LICENSE).
