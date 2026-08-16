# DS3 Charger

A small Android app that makes a Sony **DualShock 3** controller actually charge over USB when plugged into an Android TV device (built/tested for an **NVIDIA Shield TV Pro**), instead of just powering on and sitting there.

## The problem

Plug a genuine PS3 controller into anything that isn't a real PS3 (a PC, an Android TV box) and it powers on but **never charges** — because only a real PS3 (or software that knows the trick) sends the USB command that switches the controller into "operational mode," which is also what enables its charging circuit.

## What this app does

- Sends the full operational-mode handshake automatically the moment a DS3 is plugged in (auto-launches via a USB device-attach intent filter — no need to open the app manually), and also on device boot if a controller is already plugged in when the Shield powers on
- **Per-device cards**: each connected controller gets its own card (name/battery/status) with its own action buttons — plug in more than one via a hub and they're tracked and charged independently
- **Low battery alert**: pops an alert notification when a controller's reported level drops to 25% — toggle in Settings, on by default. 25%, not a rounder number, because it's the nearest tier the DS3 hardware can actually report. Works whether the controller is on USB (connected but not charging) or genuinely wireless over Bluetooth (Android 12+ only - see the note below)
- Reads and displays battery status by polling the controller's own USB HID input report while plugged in (interval adjustable in Settings — 1/5/15/30 min, default 5), and separately via Android's own input-device battery tracking while running wirelessly (Android 12+ only - see the note below). **Live percentage only while genuinely not charging or fully charged** — see the battery-byte note below for why a real number can't be shown while actively charging
- Runs as a foreground service with a persistent notification, so charging status keeps updating even after you leave the app
- **Charge-complete alert**: pops a separate, actually-alerting notification (not just a silent status update) the moment a controller finishes charging — toggle in Settings, on by default. This is the one real "next charging level" event the DS3 hardware exposes; it only reports live battery % while *not* charging (see [How it works](#how-it-works)), so there's no granular 25/50/75% alert while plugged in, just the Charging → Full transition
- Handles more than one DS3 plugged in at once (e.g. via a USB hub) — the charge-complete alert fires independently per controller
- **Check Authenticity**: samples the analog stick/button ADC resolution while you move a stick or press a button, since genuine Sony hardware uses a real (~10-bit) ADC and cheap clone boards commonly upscale a coarser one — producing visibly stepped values on a slow sweep instead of smooth ones. Heuristic, not proof, but based on real hardware behavior, not a spoofable identifier
- **Test Rumble**: fires both motors in an alternating 1-second pattern, so you can confirm both actually work
- **Pair to Host...**: writes a chosen host's Bluetooth MAC into the controller's stored "master" address over USB — the same mechanism a real PS3 (or a PC tool like SixaxisPairTool) uses, since DS3 pairing is USB-write-driven, not a self-contained discoverable Bluetooth mode. You type in the target MAC each time; nothing device-specific ships hardcoded
- Settings screen (TV-remote-friendly preset buttons, no on-screen keyboard needed) for the poll interval and the charge-complete alert toggle — changes apply on the next poll, no restart needed

## How it works

Everything here is a raw USB HID control transfer via Android's `UsbManager`/`UsbDeviceConnection` APIs (no root needed) — no PS3, no proprietary driver. The exact bytes are verified against the real Linux kernel source (`drivers/hid/hid-sony.c`) and cross-checked against a second, independent, actively-maintained real driver ([DsHidMini](https://github.com/nefarius/DsHidMini)), not guessed:

- **Enter operational mode / start charging**: `sixaxis_set_operational_usb()` — **two** `HID GET_REPORT` calls, not one. Step 1 is Feature report `0xF2` (17 bytes); step 2 is Feature report `0xF5` (8 bytes), which the kernel's own comment describes as needed by "some compatible controllers... to get operational." **This app only ever sent step 1 until v1.3.0** — meaning a controller could enumerate and report a plausible-looking battery status while its charging circuit may never have actually engaged. If your controller was slow to charge or you're upgrading from an older release, this is why.
- **Battery level**: byte 30 of the standard 49-byte input report. `0xee`/`0xef` = charging/full; otherwise a 0–5 index into `{0, 1, 25, 50, 75, 100}` (not a raw percentage). Confirmed at this same offset independently in DsHidMini's own `DS3_RAW_INPUT_REPORT` struct. **While actively charging (`0xee`), the DS3's own charge controller owns this byte and never reports a real percentage — only the Charging/Full state** (distinguished by one bit). Earlier versions displayed a hardcoded `100%` for both, which was misleading since Charging could mean anywhere from empty to nearly full; as of this fix it shows `Charging...` with no number instead, and only shows a real `100%` once it's genuinely Full. This is a firmware limitation, not something fixable by better parsing, more frequent polling, or root — the protocol has been reverse-engineered thoroughly enough (by this project and by others, including DsHidMini) that there's high confidence no other USB report on this hardware carries a live charging percentage either.
- **Rumble**: a 36-byte OUTPUT report (Report ID `0x01`) — byte 3 = right (small) motor on/off, byte 5 = left (large) motor force (0–255). Sent via both the control-transfer `SET_REPORT` path and the controller's interrupt OUT endpoint, since the kernel driver notes some boards only honor the latter.
- **Bluetooth pairing write**: an 8-byte `SET_REPORT` to Feature report `0xF5` — `[0x01, 0x00, mac0..mac5]`, MAC in natural byte order. Format sourced from Android's own historical bluez `sixpair.c` (`set_master_bdaddr`).
- **Authenticity check's stick/button offsets**: bytes 6–9 (left/right stick X/Y), bytes 14–25 (12 analog pressure values: D-pad×4, L2/R2/L1/R1, Triangle/Circle/Cross/Square). These aren't hand-decoded by the kernel driver (generic HID, mapped from the device's own report descriptor), so they're sourced from a community protocol reference and cross-checked against DsHidMini's struct.

The USB interface is claimed only for the duration of each individual transfer, not held continuously — holding it exclusively was tried early on and broke the controller's normal use as a Bluetooth gamepad while it was plugged in charging.

## What this app deliberately does NOT do

- **A full-charge LED indicator was tried and removed.** Lighting all 4 LEDs when the battery hits Full worked at the USB protocol level (confirmed via a real interrupt-endpoint output-report write), but Android itself assigns a connected DS3 a real gamepad "player slot" (`ControllerNumber` in `dumpsys input`) and re-asserts its own single player-indicator LED on the same report — even a few-times-a-second re-write from this app couldn't hold a steady all-4 state against it. The notification's `100% Full` text is the actual full-charge indicator now.
- **Android TV does not auto-pair a DS3 the way a real PS3 does — correcting an earlier, wrong assumption in this README.** A previous version of this doc claimed Android's `hid-sony` kernel driver automatically writes the host's Bluetooth address into a plugged-in controller (the same mechanism real PS3s and PC pairing tools use, Feature report `0xF5`). That's not actually true on Android TV: `hid-sony` is a *Linux desktop/server* kernel driver, and there's no evidence Android's own Bluetooth/USB HID stack performs that write on plug-in. Live testing (2026-08-13) confirmed a DS3 does **not** wirelessly reconnect to a Shield on its own after its stored pairing address is reset, even after a correct USB re-pair write and a fresh Bluetooth bond attempt — the controller's own firmware terminated the connection every time (`reason:19`, remote-terminated), suggesting a real, unresolved Bluetooth-stack compatibility gap between the DS3's ~2006-era pairing protocol and at least this Shield's Android Bluetooth stack. The **Pair to Host...** button (new in v1.3.0) at least gets the correct address written into the controller — same mechanism a real PS3 uses — but full wireless reconnection to an Android TV device is not guaranteed to complete even so, and isn't something further changes to this app's USB code are likely to fix. If wireless use matters more than charging, a real PS3 (or a PC with a proper DS3 Bluetooth driver) remains the reliable way to pair one.

- **Battery % while running wirelessly over Bluetooth (unplugged) may not work, depending on your Android version.** This app tries two paths: the real public `InputDevice.getBatteryState()` API (Android 12/API 31+, works cleanly, no extra permission), and a fallback for older versions using `BluetoothDevice.getBatteryLevel()` - an undocumented, `@hide` AOSP method not in the public SDK, used here anyway since there's no public alternative pre-12. On Android 11 and below this fallback can still come back empty: the DS3's real battery data lives in a kernel `power_supply` node (`/sys/class/power_supply/sony_controller_battery_.../capacity`, confirmed present via the kernel's `hid-sony` driver) that Android only started bridging into the input framework in API 31 - the hidden Bluetooth API draws from a *different, unrelated* data source and has nothing to report for a device paired that way on older OS versions. The only way around this is reading that kernel file directly, which needs root. Battery % over **USB** (plugged in) always works regardless of Android version, since that goes through this app's own direct HID reads, not either of the above.

## A note on the charge-complete alert not showing up

Whether the charge-complete alert actually pops on screen depends on your Android TV **launcher**, not this app — unlike phones, Android TV doesn't render heads-up notification banners at the system level, that's up to whichever launcher app is set as default. Stock FLauncher, for example, doesn't implement notification overlays at all (the notification is still posted correctly, it just never displays). If yours doesn't show it, either check your launcher for a "notification overlay" style setting, or switch to one that has it (e.g. [LTvLauncher](https://github.com/LeanBitLab/LtvLauncher)).

## Requirements

- An Android device with USB host support (tested on NVIDIA Shield TV Pro)
- A genuine Sony DualShock 3, or a Shanwan/Gasia clone — clones report the exact same USB vendor/product ID (`054c:0268`) as a real DS3, so they're detected and charged the same way
- Android 5.0 (API 21) or newer

## Install

Grab the APK from [Releases](../../releases) and sideload it:

```
adb connect <device-ip>
adb install app-debug.apk
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


## Support

If this saved you time or you just want to say thanks:

**Cash App:** [$CVanZetta](https://cash.app/$CVanZetta)
