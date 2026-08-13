# Mhz_Localiser

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/TFD-42/Mhz_Localiser/actions/workflows/ci.yml/badge.svg)](https://github.com/TFD-42/Mhz_Localiser/actions/workflows/ci.yml)
![Platform: Flipper Zero](https://img.shields.io/badge/platform-Flipper%20Zero-FF8200)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Link: USB · BLE](https://img.shields.io/badge/link-USB%20%C2%B7%20BLE-5A29E4?logo=bluetooth&logoColor=white)
![Spectrum DB: offline](https://img.shields.io/badge/spectrum%20DB-offline-2ea043)
![Status: beta](https://img.shields.io/badge/status-beta-yellow)

**RF signal triangulation + spectrum allocation lookup** — Flipper Zero streams live RSSI over **USB or Bluetooth LE** to an Android app that logs GPS + signal, estimates the transmitter location on a map, and lets you look up the regulatory allocation (USA, ITU, EU per country) of any frequency you observe.

**Contents:** [How it works](#how-it-works) · [What's new in v2.1](#whats-new-in-v21) · [Operational workflow](#operational-workflow) · [Field validation](#field-validation) · [Physical limitations](#physical-limitations) · [vs. real RF gear](#comparison-with-real-rf-equipment) · [Use cases](#realistic-use-cases) · [Quick start](#quick-start) · [Allocation list](#allocation-list) · [Build from source](#build-from-source) · [Data protocol](#data-protocol-usb--ble)

<img width="1254" height="1254" alt="ChatGPT Image 16 mai 2026 à 03_50_03" src="https://github.com/user-attachments/assets/5aa42b1d-563e-409e-b09f-10ac508359c3" />

> **Honest disclaimer:** This is a low-cost, mobile RSSI-based approach. It gives approximate results, not GPS-grade precision. Read the *Physical Limitations* section before drawing conclusions from the output.

```
Mhz_Localiser/
├── artifacts/              ← Ready-to-flash binaries
│   ├── rf_logger.fap       Copy to /ext/apps/Sub-GHz/ on the Flipper SD card
│   └── RF_Triangulator.apk Sideload or `adb install -r`
│
├── flipper/                ← Flipper Zero FAP source (build with ufbt)
│   ├── rf_logger.c         Manual MHz digit editor only — no presets (v2)
│   ├── application.fam     ufbt manifest
│   └── rf_logger_icon.png
│
├── android/                ← Capacitor sources for the Android app
│   ├── www/
│   │   ├── index.html      Triangulator + Allocation List tabs
│   │   ├── app.js          map / capture / Nelder-Mead + allocation logic
│   │   ├── styles.css      mobile-first dark theme
│   │   └── spectrum.csv    ~2 450 spectrum allocations (offline)
│   └── plugin/
│       ├── FlipperSerialPlugin.java   Native USB-CDC bridge
│       └── FlipperBlePlugin.java      Native Bluetooth-LE bridge (GATT serial)
│
├── spectrum_scraper/       ← Python tooling that builds spectrum.csv
│   ├── enrich_spectrum.py  baseline → long-form CSV (EFIS optional)
│   ├── launcher.py         interactive CLI: list / lookup by MHz
│   ├── data/               baseline CSVs (USA, ITU, EU per country)
│   └── output/             generated long-form CSV
│
├── README.md               this file
├── SETUP.md                build and install instructions
└── LICENSE                 MIT
```

## What's new in v2.1

| Change | Detail |
|--------|--------|
| **Bluetooth LE streaming** | The Flipper now streams the same CSV over **both USB and BLE** simultaneously. Go fully wireless — no cable between Flipper and phone. |
| **Dual transport picker** | Choose **USB** or **Bluetooth** in the drawer; the solver, map, and allocation lookup are identical on either. |
| **BLE device picker** | Scans for the Flipper by serial-service UUID and by name; custom-named units (no "Flipper" prefix) are listed and the chosen device is remembered for one-tap reconnect. |
| **Robust BLE connect** | Forces the LE transport, self-heals advertising, refreshes Android's stale GATT cache, and binds the correct data characteristic (INDICATE) — so it survives re-pairs and firmware re-flashes. |
| **On-device BT status** | The Flipper running screen shows `BT:off / BT:adv / BT:ok` so you can see the link state at a glance. |
| **RSSI smoothing** | Exponential moving-average filter on the live stream damps single-sample multipath spikes before capture. |
| **Selectable environment / path-loss `n`** | Pick open-field / suburban / urban / dense / indoor (or a custom `n`) in the drawer — the single biggest lever on accuracy. |
| **Automatic outlier rejection** | Leave-one-out residual check flags and excludes likely-multipath captures before the final solve. |
| **Live capture-geometry hint** | Warns when captures are too collinear (ambiguous fix) so you spread out for better geometry. |
| **Session persistence** | Captures + settings survive an app restart (stored locally), so a field session isn't lost. |

## What's new in v2

| Change | Detail |
|--------|--------|
| **Manual frequency only** | Preset menu (315 / 433 / 868 / 915 MHz) removed. App opens directly on the digit editor at launch. |
| **Faster startup** | No menu navigation — enter frequency and press OK immediately. |
| **Smaller FAP** | 8.5 KB → 7.5 KB (preset table and menu renderer eliminated). |
| **Auto-reconnect** | Android plugin reconnects automatically every 2 s if USB is lost — no need to tap Connect again. |
| **Synced allocation panel** | Panel 2 driven entirely by the live Flipper stream — no offline frequency input required. |
| **Compact allocation view** | Single dense table: MHz → Country → Region → Service → Status → Application → Source. No tab-swap needed. |
| **Location permissions** | Fine + Coarse GPS permissions wired up at runtime on first launch. |

## How it works

- **Flipper Zero** runs `rf_logger.fap` — a Sub-GHz RSSI logger that continuously samples signal strength on a chosen frequency and streams readings as CSV over **USB CDC and Bluetooth LE at the same time**.
- **Android app** (`RF_Triangulator.apk`) connects to the Flipper over **USB-C or Bluetooth LE** (your choice), reads the live RSSI stream, and logs GPS + signal captures on a map.
- With 3 or more captures from different positions, the app runs a **Nelder-Mead least-squares solver** to estimate the transmitter location.

<img width="3200" height="3200" alt="layout-collage-1779109255380" src="https://github.com/user-attachments/assets/6854f5c0-e2ab-4062-8f6e-4ff474ce8be5" />

<img width="709" height="1536" alt="1000722535" src="https://github.com/user-attachments/assets/6889242d-29a7-45f7-bb6f-4333b7022c85" />

<img width="709" height="1536" alt="list" src="https://github.com/user-attachments/assets/9017b787-0ee8-4b15-8c35-169e737cbf49" />

## RF Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Flipper Zero                             │
│                                                                 │
│   CC1101 chip  ──►  RSSI register  ──►  rf_logger.fap          │
│   (Sub-GHz)        sampled @ 5 Hz      (FAP app)               │
└──────────────┬───────────────────────────────┬─────────────────┘
               │  USB CDC-ACM (ch1)             │  BLE GATT serial
               │  115200 baud, dual CDC         │  service 0xfe60,
               │                                │  TX char indicate
               │  CSV: ts_ms, req_hz, act_hz, rssi_dbm, rssi_raw, lqi, n
               ▼                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Android App                                │
│                                                                 │
│  FlipperSerialPlugin  ─┐                                        │
│  FlipperBlePlugin    ─┴►  transport picker (USB / BLE)          │
│  (native Java)            parses CSV stream, auto-reconnect     │
│                                                                 │
│  Geolocation API      ──►  GPS coordinates                      │
│                                                                 │
│  Capture engine       ──►  {lat, lon, rssi, freq}  x N         │
│                                                                 │
│  Log-distance model   ──►  RSSI → estimated distance           │
│                                                                 │
│  Nelder-Mead solver   ──►  trilateration estimate              │
│  (non-linear LSQ)          + RMS residual error                │
│                                                                 │
│  Leaflet map          ──►  circles + estimated TX marker       │
└─────────────────────────────────────────────────────────────────┘
```

The CC1101 chip inside the Flipper Zero is a general-purpose Sub-GHz transceiver. Its RSSI register is read at 5 Hz and converted to dBm using the standard formula. The Android app receives this as a raw telemetry stream — it is a **mobile RF telemetry pipeline**, not a direction-finding antenna system.

## Flipper app flow (v2)

```
Launch
  └─► Manual frequency entry screen (default: 433.92 MHz)
        Up/Down    — increment/decrement active digit
        Left/Right — move cursor across XXX.XX MHz display
        OK         — validate & start RSSI streaming
        Back       — exit app

Running state
  └─► Live RSSI streamed over USB + BLE CSV to Android
        Status line shows BT:off / BT:adv / BT:ok
        OK   — toggle SD card logging on/off
        Back — return to frequency entry screen
```

> **Bluetooth note:** enable **Settings → Bluetooth** on the Flipper so the app can advertise. Keep the official Flipper mobile app closed while connected here — only one BLE central can hold the link at a time.

## Operational Workflow

Most people imagine: *"point the Flipper, app finds the source instantly."*
The reality is **mobile statistical inference**. Here is the actual workflow:

```
1. ENTER FREQUENCY
   └─ Dial the target frequency digit by digit on the Flipper
      (300–928 MHz range, 10 kHz resolution)

2. VERIFY SIGNAL
   └─ Confirm RSSI is above noise floor (> -100 dBm)
      before moving — if signal is too weak, get closer first

3. MOVE AROUND THE AREA
   └─ Walk to 3–6 positions that SURROUND the suspected source
      Positions in a straight line = ambiguous fix
      Spread of 90°+ between capture points = best geometry

4. CAPTURE GPS + RSSI AT EACH POSITION
   └─ Tap "Capture here" or enable Auto-capture
      Each capture = {lat, lon, rssi_dBm, freq_Hz}

5. SOLVER RUNS AUTOMATICALLY
   └─ Nelder-Mead minimises sum of squared residuals
      across all distance circles
      Output: estimated lat/lon + RMS error in metres

6. INTERPRET THE ESTIMATE
   └─ Low RMS (<30 m)  = circles agree well, high confidence
      High RMS (>100 m) = noisy environment or bad geometry
      Re-capture from better positions if RMS is high
```

## Field Validation

Tests conducted with a 433.92 MHz transmitter (generic keyfob, ~10 dBm output), Flipper Zero hardware, and a Pixel 7 with GPS lock (accuracy ±5–8 m).

| Environment        | Captures | Real distance | Estimated distance | RMS error | Notes |
|--------------------|----------|---------------|--------------------|-----------|-------|
| Open field         | 5        | 120 m         | 133 m              | 13 m      | Clean line-of-sight, flat terrain |
| Open field         | 4        | 80 m          | 91 m               | 22 m      | Light wind, same conditions |
| Suburban street    | 5        | 65 m          | 88 m               | 38 m      | Parked cars, low buildings |
| Dense urban        | 6        | 80 m          | 210 m              | 130 m     | Multi-storey buildings, reflections |
| Dense urban        | 5        | 50 m          | 145 m              | 95 m      | Corner geometry, NLOS |
| Indoor (office)    | 4        | 30 m          | unstable           | —         | Walls, furniture, multipath — unusable |
| Indoor (warehouse) | 5        | 40 m          | 67 m               | 51 m      | Large open space, concrete walls |

**Key takeaways:**
- Open field with good capture geometry: error under 25 m consistently.
- Suburban environments: 30–60 m error is realistic.
- Dense urban or indoor: results degrade sharply. Use as a search area, not a precise fix.
- Captures in a straight line (bad geometry) inflated RMS by 3–4× vs. well-spread captures.

## Physical Limitations

### RSSI ≠ distance

The log-distance path loss model used here is:

```
d = d₀ · 10^( (PL(d) − PL(d₀)) / (10 · n) )

where:
  d₀ = 1 m (reference distance)
  n  = path-loss exponent (3.0 default — urban/cluttered)
  PL = path loss in dB = Tx_power − RSSI
```

This model assumes that signal decays smoothly and predictably with distance. In practice:

| Assumption          | Reality |
|---------------------|---------|
| Free propagation    | Reflections from buildings, vehicles, ground |
| Single path         | Multipath — multiple copies of signal arrive with different phases |
| Static environment  | People walking, doors opening change RSSI by 5–15 dB |
| Isotropic antenna   | Flipper's PCB antenna has directional variation |
| Known Tx power      | Actual output may differ from nominal |

**Multipath is the primary failure mode.** In urban environments, a signal reflected off a building wall can arrive stronger than the direct path, making the solver place the estimated source in completely the wrong direction. This is why the dense urban results above show 130 m error for an 80 m actual distance.

The path-loss exponent `n` is the biggest tuning knob:
- `n = 2.0` — free space, vacuum or very open field
- `n = 2.7–3.5` — typical outdoor urban
- `n = 3.5–5.0` — indoor, heavy obstruction

Wrong `n` = systematically biased distance estimates = bad triangulation even with good geometry.

### What this tool **cannot** do

- ❌ Real-time tracking of a moving transmitter
- ❌ Sub-10 m precision in any environment
- ❌ Direction finding (it has no antenna array)
- ❌ Work reliably indoors without tuning `n` per-room
- ❌ Replace dedicated DF equipment for serious applications

## Comparison with Real RF Equipment

| Solution                       | Method                                | Typical accuracy           | Cost          |
|--------------------------------|---------------------------------------|----------------------------|---------------|
| **Mhz_Localiser (this project)**| RSSI trilateration, mobile           | 15–150 m depending on env. | ~$60 (Flipper + phone) |
| KrakenSDR                      | 5-element coherent SDR phase array    | 2–10 m outdoors            | ~$500         |
| HackRF + directional antenna   | Manual DF, SDR                        | 5–20 m with skill          | ~$350 + antenna |
| RTL-SDR + Doppler DF           | Software Doppler shift                | 10–30 m                    | ~$30 + antenna |
| Professional TDOA systems      | Time-difference of arrival            | <1 m                       | $5,000–$50,000 |

Mhz_Localiser is the **lowest cost and lowest barrier** option. It trades precision for accessibility — the entire system fits in a pocket and requires no RF expertise to operate. For hobbyist fox-hunting, lost-sensor searches, or educational RF experiments, the 15–50 m accuracy in open environments is genuinely useful.

## Realistic Use Cases

### Good fits ✓
- **Lost LoRa sensor or tracker** — device is stationary, open outdoor environment, you just need to narrow a search area to ~50 m
- **RF interference source hunt** — approximate location of a jammer or misbehaving device in a building or parking lot
- **Keyfob / garage door transmitter** — short range, open space, works well
- **Educational RF experiments** — understanding path loss, multipath, trilateration geometry
- **Sub-GHz survey** — mapping signal coverage of a fixed transmitter across a site
- **Amateur radio fox-hunting** — recreational hidden transmitter hunt

### Poor fits ✗
- **Precise tracking** — 15 m minimum error even in ideal conditions
- **Indoor room-level localisation** — multipath makes results unreliable
- **Moving transmitter** — solver assumes static source
- **Surveillance or legal evidence** — far too imprecise and unvalidated for any serious application
- **Dense urban precision** — error easily exceeds 100 m

## Quick Start

### Step 1 — Install the Flipper app

Copy `artifacts/rf_logger.fap` to your Flipper SD card:
```
/ext/apps/Sub-GHz/rf_logger.fap
```

On the Flipper: **Apps → Sub-GHz → RF Logger**

- App opens directly on the **manual frequency entry** screen (433.92 MHz default).
- **↑/↓** change the active digit · **←/→** move the cursor across `XXX.XX MHz` · **OK** starts the scan · **Back** exits.
- Range clamped to 300–928 MHz (CC1101 Sub-GHz hardware limit).
- In running mode: **OK** toggles SD card logging on/off · **Back** returns to frequency entry.

### Step 2 — Install the Android app

Enable **Install unknown apps** in Android settings, or use ADB:
```bash
adb install artifacts/RF_Triangulator.apk
```

Grant **Location** permission when prompted (required for GPS capture).

### Step 3 — Connect and capture

Open **RF Triangulator** → tap ☰ and pick your transport:

**USB (wired)**
1. Plug the Flipper into your Android phone with a USB-C cable.
2. Transport **USB** → **Connect Flipper** → accept the USB permission dialog. Auto-reconnects if USB drops.

**Bluetooth (wireless)**
1. On the Flipper: **Settings → Bluetooth ON**, then launch RF Logger (status shows `BT:adv`).
2. Transport **Bluetooth (BLE)** → **Connect Flipper** → pick your Flipper from the list → accept pairing on both devices. Its address is remembered for one-tap reconnect next time.

Then, on either transport:

3. The top panel mirrors the Flipper: frequency, RSSI, signal bar.
4. Walk to a position and tap **Capture here** — the app logs GPS + live RSSI.
5. Repeat from **3+ different positions** surrounding the suspected transmitter.
6. The drawer shows the **triangulation estimate** with RMS error once you have 3+ captures. Tune the **environment / path-loss `n`** there for your surroundings.

### Auto-capture mode

Tap **Auto-capture** to log readings automatically at a set interval (1 s / 2 s / 5 s / 10 s) while you walk around. Tap **Stop Auto** to end.

### Import Flipper files

Tap ☰ → **Load .sub / .log** to import files captured directly on the Flipper SD card. Supports `.sub`, `.log`, `.csv`, `.txt` with `RSSI:` and `Latitude/Longitude:` fields.

### Export

- **CSV** — one row per capture: `id, lat, lon, rssi_dbm, freq_hz, source`
- **JSON** — full capture list + triangulation estimate

## Allocation List

A second tab in the Android app lets you look up the regulatory allocation of any frequency without leaving the app — driven live from the Flipper stream, no manual input needed.

Each result shows: **band · country · region · service** (FIXED / MOBILE / AMATEUR / SRD ISM / BROADCASTING / …) · **status** (PRIMARY / Secondary) · **application** (LoRa, GSM 900, Wi-Fi 2.4 GHz, TETRA, NFC, …) · **source** (FCC 47 CFR 2.106, ITU RR, ARCEP, BNetzA, Ofcom, CNAF, ECC/DEC, ERC/REC 70-03, …).

Data is bundled into the APK as `spectrum.csv` — ~2 450 rows covering ITU R1/R2/R3, USA federal + non-federal, and per-country EU allocations. Fully offline, no network required.

## Build from Source

### Flipper FAP (ufbt — recommended)

```bash
pip install ufbt
cd flipper/
ufbt build
# Output: ~/.ufbt/build/rf_logger.fap

# Deploy directly to connected Flipper
ufbt launch
```

### Flipper FAP (fbt — full firmware repo)

```bash
cp -r flipper/ flipper-firmware/applications_user/rf_logger
cd flipper-firmware
./fbt fap_rf_logger
```

### Android APK

Requires Android Studio, Java 17+, Node.js 18+.

```bash
cd android/
npm install
npx cap sync android
cd android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Stack: **Capacitor** · **usb-serial-for-android** (CDC-ACM) · **Android BLE** (`BluetoothGatt`, GATT serial) · **Leaflet** (OpenStreetMap)

Both native bridges (`FlipperSerialPlugin.java`, `FlipperBlePlugin.java`) must be registered in `MainActivity` and expose the same `connect`/`disconnect`/`data`/`status` interface, so the web UI treats USB and BLE as interchangeable.

## Data Protocol (USB + BLE)

Flipper streams the **same CSV** over USB CDC-ACM channel 1 at **115200 baud** and over **Bluetooth LE** (GATT serial service `0000fe60-…`, data on the TX characteristic `0000fe61-…` via indications). The Android side parses both identically:

```
# RF_LOGGER_DBG req=433920000 act=433920000
ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n
1234567,433920000,433920000,-85,0x57,12,1
1234767,433920000,433920000,-83,0x59,14,2
```

| Column     | Type    | Description |
|------------|---------|-------------|
| `ts_ms`    | uint64  | Uptime milliseconds |
| `req_hz`   | uint32  | Requested frequency Hz |
| `act_hz`   | uint32  | Actual tuned frequency Hz |
| `rssi_dbm` | int     | Signal strength dBm |
| `rssi_raw` | hex     | Raw CC1101 RSSI register byte |
| `lqi`      | uint8   | Link Quality Indicator |
| `n`        | uint32  | Sample counter |

Sample rate: **200 ms (5 Hz)**. The Android app auto-detects frequency from `req_hz` — no manual input on the phone side needed. BLE notify packets are reassembled by newline, so line framing is transport-independent.

## Security

- No API keys, tokens, or credentials in this codebase.
- No hardcoded IP addresses — all communication is local (USB or Bluetooth LE), device-to-phone only.
- USB permission via standard Android intent (`UsbManager.requestPermission`); Bluetooth via runtime `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` permissions (scan flagged `neverForLocation`).
- Location permission requested at runtime only (for GPS capture).
- No outbound network requests from the app (map tiles load from OpenStreetMap via WebView only).

## Requirements

| Component    | Requirement |
|--------------|-------------|
| Flipper Zero | Firmware 0.97+ (official or Unleashed); Bluetooth ON for wireless mode |
| Android      | 8.0+ (API 26); USB OTG **or** Bluetooth LE |
| Connection   | USB-C cable (USB-A OTG adapter), **or** Bluetooth LE — no cable needed |
| GPS          | Required for capture; indoor = poor accuracy |

## License

MIT — see [LICENSE](LICENSE).
