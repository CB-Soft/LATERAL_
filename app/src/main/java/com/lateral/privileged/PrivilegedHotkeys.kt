package com.lateral.privileged

/**
 * Hotkey codes the shell-uid [PrivilegedServer] forwards to the app-side dispatcher when a
 * global Ctrl+Alt+X combo fires. Shared by both sides of the AIDL boundary
 * ([IPrivilegedHotkeyListener.onHotkey]).
 *
 * The combinations mirror the Windows companion app at
 * `..\Windows\app\main.cpp`, so muscle memory is the same on both platforms.
 *
 *  - Ctrl+Alt+= / +     → [HK_ZOOM_IN]            → `WorkspaceController.pinch(1.25)`
 *  - Ctrl+Alt+-         → [HK_ZOOM_OUT]           → `WorkspaceController.pinch(1/1.25)`
 *  - Ctrl+Alt+Z         → [HK_CYCLE_SCREEN_BAND]  → `WorkspaceController.cycleScreenBand()`
 *  - Ctrl+Alt+X         → [HK_TOGGLE_VIEW_MODE]   → `WorkspaceController.setViewMode(opposite)`
 *  - Ctrl+Alt+R         → [HK_SDK_RECENTER]       → `NativeGlasses.resetOriginCarina()`
 *  - Ctrl+Alt+C         → [HK_ANCHOR_POSE]        → `WorkspaceController.alignVerticalToHead()`
 *
 * Ctrl+Alt+Wheel is handled in `MainActivity.dispatchGenericMotionEvent` directly — the
 * keyboard modifier state rides on the MotionEvent, so the activity sees it without needing
 * the privileged spy.
 *
 * The set was originally Win+Shift+X to mirror Windows' WH_KEYBOARD_LL handler exactly, but
 * Samsung One UI hard-binds Meta to its launcher's app-drawer shortcut, so any Win combo
 * also opens the drawer. Ctrl+Alt is free on both sides.
 */
object PrivilegedHotkeys {
    const val HK_ZOOM_IN = 1
    const val HK_ZOOM_OUT = 2
    const val HK_CYCLE_SCREEN_BAND = 3
    const val HK_TOGGLE_VIEW_MODE = 4
    const val HK_SDK_RECENTER = 5
    const val HK_ANCHOR_POSE = 6

    /**
     * Synthetic "modifier-pair" transitions — fired when Ctrl AND Alt first become
     * both held (DOWN) and when the combined state becomes false again (UP). Drives
     * the keymap-legend overlay on the glasses, matching the Windows companion's
     * "Ctrl+Alt is held" legend cue.
     */
    const val HK_MODIFIERS_DOWN = 7
    const val HK_MODIFIERS_UP = 8

    /** Cycle FREE-mode layout (SINGLE → VHV → … ). No-op in PINNED. Android-only —
     *  the Windows companion has no equivalent "scene layout" concept. */
    const val HK_CYCLE_LAYOUT = 9
}
