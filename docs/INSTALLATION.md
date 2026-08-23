# LATERAL_ installation guide

LATERAL_ is an experimental, target-hardware Android workspace. It has been
tested only on a Nubia NX789J running Android 15 with VITURE Beast glasses
connected as an external display.

Other phones, Android versions, glasses, displays, and firmware revisions are
untested. Hosted-app behavior depends on Android's privileged virtual-display
and IME services, so re-test after system updates.

## Install the published APK

1. On the phone, enable **Developer options** and **Wireless debugging**.
2. Download `app-debug.apk` from the [v0.1.0 GitHub release](https://github.com/CB-Soft/LATERAL_/releases/tag/v0.1.0).
3. Allow the browser or file manager to install that APK, then install it.
4. Connect the Beast glasses and launch LATERAL_ from the phone.
5. In PhoneUI, pair/start the privileged helper when prompted. Use Android's
   Wireless debugging **pair with code** flow and submit the code in the
   LATERAL_ notification.
6. Use PhoneUI as the touchpad and control surface. BeastUI should appear on
   the external display; hosted apps and display controls are selected from
   PhoneUI.

The release APK is a debug-signed development build, not a Play Store build.
Normal phone apps remain phone-hosted; only apps explicitly hosted through the
Beast workspace are moved to the external display.

## Build from source

Install the following locally:

- Android Studio with JDK 17.
- Android SDK Platform 37 and Build Tools suitable for the project.
- Android NDK `30.0.14904198` and CMake `4.1.2`.
- Git and ADB.
- The official [VITURE XR Glasses SDK](https://www.viture.com/en-US/developer),
  downloaded under the SDK terms from VITURE's developer portal.

The VITURE SDK is not included in this repository. The current native build
expects the SDK's Android headers and ARM64 `libglasses.so` in the local
UxSpace-compatible layout:

```text
<workspace>/uxspace/Android/glasses/src/main/
├── cpp/include/       # VITURE SDK headers
└── jniLibs/arm64-v8a/libglasses.so
```

Keep that SDK directory private and do not commit it. If your SDK download uses
a different layout, update the local CMake/source-set paths to point at the
SDK without adding the SDK files to Git.

From the LATERAL_ repository root:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then follow the published-APK first-use steps above. A source build still
requires a connected Beast for the VITURE-specific controls and Wireless
debugging pairing for hosted apps.

## SDK and redistribution notice

VITURE publishes the SDK through its developer portal and provides the official
SDK documentation and downloads there. The SDK's headers and native runtime are
separate from LATERAL_'s Apache-2.0 source. Review the license or developer
agreement supplied with the SDK before distributing any APK that contains its
native runtime. Do not redistribute the SDK archive, headers, or native
libraries unless VITURE's terms expressly permit it.

The LATERAL_ source repository intentionally does not contain the SDK. The
project's `LICENSE` covers LATERAL_-specific source; `LICENSE-UXSPACE` and
`NOTICE` cover the retained UxSpace attribution.

## Troubleshooting

- **No hosted apps / “Keyboard routing unavailable”**: pair or restart the
  privileged helper and confirm Wireless debugging is enabled.
- **BeastUI appears on the phone**: reconnect the glasses and relaunch
  LATERAL_; the external display must be available before BeastUI can attach.
- **Black hosted app card**: reconnect or minimize/restore the card to trigger
  surface reattachment.
- **Build cannot find `libglasses.so` or VITURE headers**: verify the private
  SDK layout above and that the SDK was obtained from VITURE directly.
