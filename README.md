# DS3 Charger

A small Android app that makes a Sony **DualShock 3** controller actually charge over USB when plugged into an Android TV device (built/tested for an **NVIDIA Shield TV Pro**), instead of just powering on and sitting there.

## The problem

Plug a genuine PS3 controller into anything that isn't a real PS3 (a PC, an Android TV box) and it powers on but **never charges** — because only a real PS3 (or software that knows the trick) sends the USB command that switches the controller into "operational mode," which is also what enables its charging circuit.

## What this app does

- Sends that operational-mode command automatically the moment a DS3 is plugged in (auto-launches via a USB device-attach intent filter — no need to open the app manually)
- Reads and displays **live battery %** by polling the controller's own USB HID input report (interval adjustable in Settings — 1/5/15/30 min, default 5)
- Lights one controller LED solid, once, when the battery reaches Full (toggle on/off in Settings)
- Runs as a foreground service with a persistent notification, so charging status keeps updating even after you leave the app
- Handles more than one DS3 plugged in at once (e.g. via a USB hub)
- Settings screen (TV-remote-friendly preset buttons, no on-screen keyboard needed) for both of the above — changes apply on the next poll, no restart needed

## How it works

Everything here is a raw USB HID control transfer via Android's `UsbManager`/`UsbDeviceConnection` APIs (no root needed) — no PS3, no proprietary driver. The exact bytes are verified against the real Linux kernel source (`drivers/hid/hid-sony.c`), not guessed:

- **Enter operational mode / start charging**: `sixaxis_set_operational_usb()` — a single `HID GET_REPORT` on Feature report `0xF2` (17 bytes)
- **Battery level**: `sixaxis_parse_report()` — byte 30 of the standard 49-byte input report. `0xee`/`0xef` = charging/full; otherwise a 0–5 index into `{0, 1, 25, 50, 75, 100}` (not a raw percentage)
- **LED control**: `sixaxis_send_output_report()` / `struct sixaxis_output_report` — a 36-byte output report, byte 10 is the LED bitmap (`LED1=0x02, LED2=0x04, LED3=0x08, LED4=0x10`)

The USB interface is claimed only for the duration of each individual transfer, not held continuously — holding it exclusively was tried early on and broke the controller's normal use as a Bluetooth gamepad while it was plugged in charging.

## What this app deliberately does NOT do

The all-4-LEDs blink you see when powering the controller on over **Bluetooth** (searching for/reconnecting to its last-paired host) can't be touched by this app, or by any app, without root — it happens before any HID connection exists, and the Android component that could intervene after connecting (`HidHostService`) is a system-only service gated behind privileged permissions no sideloaded app can hold.

## Requirements

- An Android device with USB host support (tested on NVIDIA Shield TV Pro)
- A genuine Sony DualShock 3 (clone/third-party controllers may need extra steps not implemented here — see `hid-sony.c`'s handling of Shanwan/Gasia/Speedlink Strike FX clones)
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

## License

MIT — see [LICENSE](LICENSE).
