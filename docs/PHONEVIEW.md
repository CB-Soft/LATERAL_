# PhoneView: issue log and intended behavior

Recorded 2026-09-12 from the user's on-device testing. Open issues, not verified fixes.

PhoneView is the new name for running the BeastUI workspace fullscreen in landscape on the phone with an external keyboard and mouse. PhoneView uses the phone's native Android cursor and keyboard input instead of LATERAL_'s managed BeastUI cursor and keyboard.

## Open issues

- PV-1: External mouse input is broken. Verify pointer movement, primary and secondary clicks, wheel scrolling, and hosted-app interaction.
- PV-2: Touchscreen taps produce right clicks. A normal tap must produce primary/touch activation; secondary activation must require the appropriate gesture or mouse button.
- PV-3: Activation and deactivation are awkward. Replace the persistent BEAST button with a PhoneView toggle in Display settings, accessible from both PhoneUI and BeastUI.

## Requested lifecycle

- PhoneView is eligible only while an external keyboard is connected and no external display is connected.
- When that condition becomes true, offer a popup asking whether to view the LATERAL_ workspace on the phone. Declining must leave the traditional mode active.
- Enabling the Display toggle enters fullscreen landscape PhoneView and switches to native local Android input.
- Disabling it restores traditional PhoneUI and managed input behavior.
- Keyboard disconnection or external-display connection automatically exits PhoneView and restores traditional mode; external-display connection hands the workspace back to the external display.
- Device changes must not create duplicate activities, repeated prompts, stranded overlays, or stale input capture.

## Source availability and validation

The imported source snapshots under builds-temp/device-trees match existing main (7bc90b2) and the older development branch (dc53c10), per the import inventory. Inspection found no PhoneView mode or persistent BEAST activation button in those snapshots. However, the stable 0.2.10 APK's saved dex dump contains MainActivity.toggleBeastWorkspace, absent from both source snapshots. The inventory's conclusion that the APKs contain only known source states is therefore unsupported: matching class sets did not establish matching implementations. The newer source that implements the reported on-phone behavior must be located before these issues can be reproduced and repaired faithfully.

The existing main display changes include hosted-app density normalization, DPI scale control, and scale-invariant supersampling. Existing fixes include modal input routing and PhoneUI focus preservation. Agent controls are reserved for LATERAL_dev; builds made from main must not expose them, including main's dev flavor.

Required PhoneView regression scenarios once its source is available: keyboard connect and accept/decline, manual toggle from either UI, keyboard disconnect, display connect/disconnect, touchscreen primary activation, external mouse buttons/wheel, native keyboard typing, repeated mode transitions, and landscape fullscreen exit.
