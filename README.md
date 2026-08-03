# DS3 Charger

A small Android app that makes a Sony **DualShock 3** controller actually charge over USB when plugged into an Android TV device (built/tested for an **NVIDIA Shield TV Pro**), instead of just powering on and sitting there.

## The problem

Plug a genuine PS3 controller into anything that isn't a real PS3 (a PC, an Android TV box) and it powers on but **never charges** — because only a real PS3 (or software that knows the trick) sends the USB command that switches the controller into "operational mode," which is also what enables its charging circuit.

## What this app does

- Sends that operational-mode command automatically the moment a DS3 is plugged in (auto-launches via a USB device-attach intent filter — no need to open the app manually)
- Reads and displays **live battery %** by polling the controller's own USB HID input report (interval adjustable in Settings — 1/5/15/30 min, default 5)
- Runs as a foreground service with a persistent notification, so charging status keeps updating even after you leave the app
- Handles more than one DS3 plugged in at once (e.g. via a USB hub)
- Settings screen (TV-remote-friendly preset buttons, no on-screen keyboard needed) for the poll interval — changes apply on the next poll, no restart needed

## How it works

Everything here is a raw USB HID control transfer via Android's `UsbManager`/`UsbDeviceConnection` APIs (no root needed) — no PS3, no proprietary driver. The exact bytes are verified against the real Linux kernel source (`drivers/hid/hid-sony.c`), not guessed:

- **Enter operational mode / start charging**: `sixaxis_set_operational_usb()` — a single `HID GET_REPORT` on Feature report `0xF2` (17 bytes)
- **Battery level**: `sixaxis_parse_report()` — byte 30 of the standard 49-byte input report. `0xee`/`0xef` = charging/full; otherwise a 0–5 index into `{0, 1, 25, 50, 75, 100}` (not a raw percentage)

The USB interface is claimed only for the duration of each individual transfer, not held continuously — holding it exclusively was tried early on and broke the controller's normal use as a Bluetooth gamepad while it was plugged in charging.

## What this app deliberately does NOT do

- **A full-charge LED indicator was tried and removed.** Lighting all 4 LEDs when the battery hits Full worked at the USB protocol level (confirmed via a real interrupt-endpoint output-report write), but Android itself assigns a connected DS3 a real gamepad "player slot" (`ControllerNumber` in `dumpsys input`) and continuously re-asserts its own single player-indicator LED on the same report — even a few-times-a-second re-write from this app couldn't hold a steady all-4 state against it. Those 4 LEDs are effectively OS-owned once Android recognizes the pad as a gamepad; the notification's `100% Full` text is the actual full-charge indicator now.
- **Bluetooth pairing needs no help from this app.** A DS3 doesn't use standard discovery/PIN Bluetooth pairing — it connects to whatever host MAC address is stored in it, normally written via a USB HID feature report (`0xF5`) while plugged in. Android's own `hid-sony` kernel driver already does this automatically on USB plug-in; a from-scratch reimplementation of that write was tested here and confirmed working (the pad paired and reconnected over BT on a PS-button press), then removed as redundant once it was clear Android already handled it without this app's involvement.

## Requirements

- An Android device with USB host support (tested on NVIDIA Shield TV Pro)
- A genuine Sony DualShock 3, or a Shanwan/Gasia clone — clones report the exact same USB vendor/product ID (`054c:0268`) as a real DS3, so they're detected and charged the same way
- Android 5.0 (API 21) or newer

## Install

Grab the APK from [Releases](../../releases) and sideload it:

```
adb connect <device-ip>
adb install ds3-charger.apk
```

## Build from source

```
git clone <this-repo>
cd ds3-charger-app
./gradlew assembleDebug
```

Or open in Android Studio and hit Run.

`assembleRelease` is minified/shrunk (R8) and needs a signing keystore referenced from a local, gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) — without one it builds an unsigned APK that `adb install` will reject.

## License

MIT — see [LICENSE](LICENSE).
