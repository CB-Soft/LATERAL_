# LATERAL_

LATERAL_ is a device-specific dual-display workspace for a Nubia NX789J phone and VITURE Beast glasses. It keeps a purpose-built **PhoneUI** on the phone and a pinned **BeastUI** workspace on the glasses, while treating Android task IDs as the identity of the apps being managed.

This is an experimental Android project for the target hardware, not a general-purpose external-display launcher.

## Intended use and tested hardware

LATERAL_ is an experimental personal dual-display workspace. PhoneUI stays on the
phone while BeastUI runs on the external display; hosting third-party apps requires
the privileged Wireless debugging pairing flow.

It has been tested only with a **Nubia NX789J running Android 15** and **VITURE
Beast glasses** connected as an external display. Other phones, Android versions,
external displays, and firmware revisions are unsupported and untested. Phone-only
keyboard routing, cursor/input injection, hosted apps, and display-mode controls
depend on the phone firmware, so re-test the privileged path after system updates.

## What it does

- **PhoneUI** provides the touchpad, cursor, app launcher, task navigator, scale controls, and input settings.
- **BeastUI** is an ordered horizontal workspace for apps on the glasses, with task decorators, taskbar tabs, minimization, fullscreen, screenshots, and a launcher overlay.
- Android tasks are continuously synchronized into one shared workspace model. Phone-launched apps stay on the phone and appear as entries in BeastUI; apps launched from LATERAL_'s launcher can be hosted on Beast.
- Multiple instances of an app remain separate because they are tracked by Android task ID, not package name.
- Closing an app with the Beast `×` control removes that exact Android task from Overview/open tasks; it does not force-stop the whole package.
- LATERAL_ keeps one PhoneUI task and one hidden, pinned BeastUI task. The Beast task is excluded from Android Overview.

## Important platform behavior

Hosting third-party apps on Beast relies on a shell-privileged helper started through Wireless debugging. The helper creates trusted virtual displays, moves individual Android tasks, injects pointer/gesture input, and manages display lifecycle.

For native text editing, LATERAL_ verifies WindowManager's fallback-display IME policy before a hosted app is used. When supported, an editable control in a Beast-hosted app opens Android's normal keyboard on the **phone**, not on the glasses. If that policy cannot be applied and read back, LATERAL_ refuses the hosted session rather than silently falling back to a keyboard-leaking external display.

Behavior therefore depends on the NX789J firmware, Android build, and the connected Beast display. Re-test the privileged path after phone or system updates.

## Requirements

- Nubia NX789J running the supported Android 15 firmware.
- VITURE Beast glasses connected as an external display.
- Developer options and Wireless debugging enabled for the initial privileged-helper pairing.
- Android Studio with JDK 17 and an Android SDK/NDK installation suitable for the project.
- ADB available when installing development builds.

## Build and install

See the complete [installation guide](docs/INSTALLATION.md) for the published
APK, source builds, VITURE SDK setup, Wireless debugging pairing, and
troubleshooting.

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On macOS/Linux, use:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-use flow

1. Connect the Beast glasses.
2. Launch LATERAL_ from the phone.
3. In PhoneUI, pair/start the privileged helper when prompted. Accept the Wireless debugging pairing request on the phone.
4. Open **Apps** to show the Beast launcher, or select an existing Android task in the shared navigator.
5. Use the PhoneUI touchpad to control the glasses workspace.

Normal Android app launches remain phone-hosted. They are imported into the Beast task list shortly afterward, where selecting one can move that exact task into the Beast workspace when it supports secondary-display hosting.

## Input and workspace controls

- One-finger motion moves the Beast cursor; tap performs a normal click.
- A deliberate hold supports drag/long-press behavior; multi-touch transition guards prevent ordinary taps from becoming long presses.
- Two-finger movement scrolls the app under the cursor first, then the Beast workspace if no app consumes it.
- Edge/taskbar scrolling moves the Beast workspace. Two-finger and scrollbar directions have independent inversion toggles.
- Pinch gestures are forwarded as a two-pointer gesture to hosted apps where supported.
- Beast focus changes do not reorder the strip. LATERAL_ only scrolls enough to reveal a focused card that is partly or completely off-screen.

Task presentation is intentionally semantic rather than freeform: `PHONE`, `BEAST_VISIBLE`, `BEAST_MINIMIZED`, and pending-hosting states, plus phone/tablet/fullscreen presentation modes. There is no drag-resize window manager.

## Scaling and Ultrawide

PhoneUI and BeastUI use one locked UI-and-text scale per display mode:

- Standard: 85–200%
- Ultrawide: 85–500%

Ultrawide mode is detected from the connected display. LATERAL_ corrects for the display's horizontal presentation characteristics and sizes app cards from the available vertical app-content height after scaled decorator chrome, so apps reflow rather than being compositor-stretched.

## Project map

- `app/src/main/java/com/lateral/MainActivity.kt` — PhoneUI and phone-side controls.
- `app/src/main/java/com/lateral/beast/BeastActivity.kt` — Beast workspace shell.
- `app/src/main/java/com/lateral/beast/WorkspaceState.kt` — shared task/workspace state.
- `app/src/main/java/com/lateral/beast/AndroidTaskSynchronizer.kt` — Android task snapshot reconciliation.
- `app/src/main/java/com/lateral/privileged/` — shell helper, trusted displays, task operations, and input injection.
- `docs/BEAST_UI.md` — BeastUI architecture and invariants.
- `LATERAL_ Outline.md` — product/design reference.
- `docs/ROADMAP.md` — planned SDK-free monitor release and future work.

## Troubleshooting

- **No hosted apps or “Keyboard routing unavailable”**: re-pair/restart the privileged helper. Do not expect Beast app hosting to work without verified IME fallback routing.
- **Black hosted app surface**: reconnect the glasses or restore/minimize the card to trigger surface reattachment. The implementation uses `TextureView` composition because the target device can render nested `SurfaceView` virtual-display queues as black.
- **External display changed mode**: allow LATERAL_ to reconfigure the workspace; it preserves the workspace model and reattaches hosted surfaces where the firmware allows it.
- **Unexpected task state**: Android Overview remains authoritative for task existence. LATERAL_ reconciles task snapshots while Beast is connected, while avoiding system, Home, LATERAL_, and known device ghost records.

## License and attribution

LATERAL_-specific code is licensed under [Apache-2.0](LICENSE). See
[NOTICE](NOTICE) and [`LICENSE-UXSPACE`](LICENSE-UXSPACE) for the retained UxSpace
attribution and license text. UxSpace is used as a reference for trusted per-app
virtual-display hosting; LATERAL_ deliberately does not reuse its freeform
drag/drop window-management model.
