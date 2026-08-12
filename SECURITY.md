# Security Policy

## Reporting a Vulnerability

If you believe you have found a security issue in Mhz_Localise — for example, a vulnerability in the USB serial plugin, a way for a malicious Flipper-pretending device to crash or take control of the Android app, or an exploitable parser bug — please report it privately.

- **Do not** open a public GitHub issue.
- Email the maintainers or use GitHub's *Report a vulnerability* feature on this repo.
- Include a description, reproduction steps, and (if possible) a minimal proof of concept.

We aim to acknowledge reports within 7 days and provide a fix or mitigation timeline within 30 days for confirmed issues.

## Scope

In scope:

- The Flipper FAP (`flipper/`) — memory safety, SD-card path handling, BLE/USB stream handling.
- The Android Capacitor plugins (`android/plugin/`) — USB intent handling, BLE GATT/pairing flow, line parsing, permission flow.
- The web UI (`android/www/`) — file import parser, exported data integrity.

Out of scope:

- General Flipper Zero firmware vulnerabilities (report upstream).
- Android OS or Capacitor framework vulnerabilities (report upstream).
- The accuracy or correctness of RSSI-based localisation — that is a documented limitation, not a security issue.

## Responsible use

This tool measures the signal strength of radio transmissions the user is already legally entitled to receive. It is intended for hobbyist, educational, and authorised use only. The project authors are not responsible for unlawful use, including unauthorised interception of communications or interference with licensed radio services. Check the regulations in your country before transmitting on or monitoring Sub-GHz bands.
