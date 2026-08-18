package com.ds3charger.app;

// Runs in a separate process spawned by Shizuku under the ADB shell UID - the only UID on
// this device confirmed able to open /dev/uinput (same real constraint the sibling
// 8bitdo-xbox-bridge project already solved this exact way). Injects the DS3's raw L2/R2
// pressure bytes as real analog trigger axes; everything else about the controller (buttons,
// sticks, D-pad, charging, rumble) is unrelated and untouched.
interface ITriggerInjector {
    // deviceId keys which uinput device a call targets (this app already tracks multiple
    // simultaneous USB DS3s by UsbDevice.deviceId - reuse that same id here rather than
    // inventing a separate player-slot concept).
    boolean openDevice(int deviceId, String name);
    void closeDevice(int deviceId);
    void sendTriggers(int deviceId, int l2, int r2);
    void destroy();
}
