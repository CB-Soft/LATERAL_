// Callback Binder the app registers with PrivilegedService so the shell-uid helper can
// forward globally-monitored Ctrl+Alt+X hotkeys back to it. See IPrivilegedService for
// the registration entry points. Hotkey codes are the HK_* constants in
// com.lateral.privileged.PrivilegedHotkeys (a Kotlin object kept in sync between sides).
package com.lateral.privileged;

oneway interface IPrivilegedHotkeyListener {
    /** A Ctrl+Alt+X combo fired. [code] is a PrivilegedHotkeys.HK_* constant. */
    void onHotkey(int code) = 1;

    /**
     * Batched relative-mouse motion, taken straight from the kernel's evdev stream
     * (EV_REL deltas summed across one SYN_REPORT group). Bypasses the system
     * cursor's screen-edge clamping — drives the workspace cursor directly so it
     * never gets stuck just because the phone-side pointer hit a phone edge.
     * [wheel] is REL_WHEEL ticks (positive = up).
     */
    void onMouseDelta(int dx, int dy, int wheel) = 2;

    /**
     * Mouse button transitioned. [code] is the kernel BTN_* code (272 left,
     * 273 right, 274 middle). [pressed] is true on press, false on release.
     * Forwarded by the helper because once it EVIOCGRABs the mouse device,
     * system_server stops seeing it — `dispatchGenericMotionEvent` would
     * otherwise miss button events too.
     */
    void onMouseButton(int code, boolean pressed) = 3;
}
