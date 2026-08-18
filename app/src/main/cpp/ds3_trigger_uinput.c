// Minimal uinput virtual gamepad for the DS3's pressure-sensitive L2/R2 analog triggers.
// Runs inside the Shizuku shell-UID process (/dev/uinput is only writable there, not from
// the main app's UID - same real constraint already confirmed and solved this exact way by
// the sibling 8bitdo-xbox-bridge project, adapted here).
//
// Deliberately axis-only, no EV_KEY buttons and no EV_FF: the DS3's existing digital
// buttons/sticks already work fine through Android's normal HID input path (this app never
// touched that), and the DS3's rumble already goes through Ds3ChargerService's own real
// USB output-report path (testRumble/pairToHost), not uinput FF - this device's only job is
// injecting the two pressure axes Android's default HID mapping doesn't expose as analog.
//
// ABS_GAS/ABS_BRAKE chosen over inventing ABS_LTRIGGER/ABS_RTRIGGER (no such uinput codes
// exist) - this is the exact same real, source-confirmed technique the sibling
// 8bitdo-xbox-bridge project already uses and live-verified: RetroArch's own
// input/drivers/android_input.c reads AXIS_LTRIGGER/AXIS_RTRIGGER/AXIS_GAS/AXIS_BRAKE via
// AMotionEvent_getAxisValue(), and Android's JoystickInputMapper compat layer derives
// LTRIGGER/RTRIGGER from GAS/BRAKE for apps that read those instead.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <android/log.h>

#define LOG_TAG "Ds3TriggerUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Headroom for more than one physical DS3 connected at once (this app already supports
// multiple simultaneous controllers) - not currently multi-player-indexed like GipBridge,
// just a free-slot pool keyed by whatever the caller passes as deviceId.
#define MAX_DEVICES 2

static int g_open_fds[MAX_DEVICES] = { -1, -1 };
static int g_open_device_ids[MAX_DEVICES] = { -1, -1 };

static void write_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        LOGE("write_event(type=%u code=%u) failed: %s", type, code, strerror(errno));
    }
}

// Name must NOT contain the substring "Virtual" -- RetroArch's Android input driver
// hardcodes a special case that relabels any device whose name contains "Virtual" as
// "SHIELD Virtual Controller" (meant for the Shield remote's own virtual device), a real
// bug already hit and fixed once this session in the sibling GipBridge project. Caller
// (Ds3ChargerService) picks the actual name; this file just never assumes otherwise.
JNIEXPORT jint JNICALL
Java_com_ds3charger_app_TriggerInjectorService_nativeOpenUinput(JNIEnv *env, jobject thiz, jint deviceId, jstring jname) {
    (void) thiz;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    int slot = -1;
    for (int i = 0; i < MAX_DEVICES; i++) {
        if (g_open_fds[i] == -1) { slot = i; break; }
    }
    if (slot < 0) {
        LOGE("nativeOpenUinput: no free device slot (MAX_DEVICES=%d)", MAX_DEVICES);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    int fd = open("/dev/uinput", O_RDWR);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_ABSBIT, ABS_GAS);
    ioctl(fd, UI_SET_ABSBIT, ABS_BRAKE);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    dev.id.bustype = BUS_USB;
    // Real Sony DS3 VID/PID - matches the actual controller these axes come from, not an
    // arbitrary placeholder.
    dev.id.vendor = 0x054C;
    dev.id.product = 0x0268;
    dev.id.version = 1;

    // Raw DS3 pressure bytes are 0-255 (see Ds3ChargerService.AUTH_CHECK_PRESSURE_OFFSETS'
    // doc comment for the source) - pass that range straight through rather than rescaling,
    // matching whatever precision the hardware actually reports instead of inventing false
    // extra resolution.
    dev.absmin[ABS_GAS] = 0; dev.absmax[ABS_GAS] = 255;
    dev.absmin[ABS_BRAKE] = 0; dev.absmax[ABS_BRAKE] = 255;

    if (write(fd, &dev, sizeof(dev)) < 0) {
        LOGE("write uinput_user_dev failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    (*env)->ReleaseStringUTFChars(env, jname, name);
    g_open_fds[slot] = fd;
    g_open_device_ids[slot] = deviceId;
    LOGI("trigger uinput device created for deviceId=%d, fd=%d slot=%d", deviceId, fd, slot);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_ds3charger_app_TriggerInjectorService_nativeCloseUinput(JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        for (int i = 0; i < MAX_DEVICES; i++) {
            if (g_open_fds[i] == fd) { g_open_fds[i] = -1; g_open_device_ids[i] = -1; break; }
        }
    }
}

JNIEXPORT void JNICALL
Java_com_ds3charger_app_TriggerInjectorService_nativeSendTriggers(JNIEnv *env, jobject thiz, jint fd, jint l2, jint r2) {
    (void) env; (void) thiz;
    // GAS=right trigger (R2), BRAKE=left trigger (L2) - same real mapping the sibling
    // GipBridge project already live-verified this session (its injectAxes() comment: "GAS=
    // right trigger, BRAKE=left trigger").
    write_event(fd, EV_ABS, ABS_GAS, r2);
    write_event(fd, EV_ABS, ABS_BRAKE, l2);
    write_event(fd, EV_SYN, SYN_REPORT, 0);
}
