# LATERAL_ installation guide

LATERAL_ is an experimental, target-hardware Android workspace. It has been
tested only on a Nubia NX789J running Android 15 with VITURE Beast glasses
connected as an external display.

Other phones, Android versions, glasses, displays, and firmware revisions are
untested. Hosted-app behavior depends on Android's privileged virtual-display
and IME services, so re-test after system updates.

## Install a local debug build

The public stable build does not contain the proprietary VITURE runtime and can
be built without requesting the VITURE SDK. To use the optional VITURE-specific
display controls, request the SDK from VITURE and follow the VITURE variant
section below. For a local SDK-free debug build:

1. Follow **Build from source** below, using the SDK-free stable command.
2. On the phone, enable **Developer options** and **Wireless debugging**.
3. Run `adb install -r app\build\outputs\apk\stable\debug\app-stable-debug.apk`.
4. Connect the Beast glasses and launch LATERAL_ from the phone.
5. In PhoneUI, pair/start the privileged helper when prompted. Use Android's
   Wireless debugging **pair with code** flow and submit the code in the
   LATERAL_ notification.
6. Use PhoneUI as the touchpad and control surface. BeastUI should appear on
   the external display; hosted apps and display controls are selected from
   PhoneUI.

Normal phone apps remain phone-hosted; only apps explicitly hosted through the
Beast workspace are moved to the external display.

## Build from source

Install the following locally:

- Android Studio with JDK 17.
- Android SDK Platform 37 and Build Tools suitable for the project.
- Android NDK `30.0.14904198` and CMake `4.1.2`.
- Rust stable with the minimal profile. FlorisBoard's build adds the
  `aarch64-linux-android` target and also requests NDK `26.1.10909125`.
- Git and ADB.
- The official [VITURE XR Glasses SDK](https://www.viture.com/en-US/developer),
  only if you want to build the optional VITURE variant. Request and download
  it under the SDK terms from VITURE's developer portal.

The VITURE SDK is not included in this repository. Only the optional VITURE
variant expects the SDK's Android headers and ARM64 `libglasses.so` in the local
UxSpace-compatible layout:

```text
<workspace>/uxspace/Android/glasses/src/main/
├── cpp/include/       # VITURE SDK headers
└── jniLibs/arm64-v8a/libglasses.so
```

Keep that SDK directory private and do not commit it. If your SDK download uses
a different layout, update the local CMake/source-set paths to point at the
SDK without adding the SDK files to Git.

From the LATERAL_ repository root, the public SDK-free debug build is:

```powershell
git submodule update --init --recursive
.\gradlew.bat :app:assembleStableDebug "-Plateral.vitureSdk=false"
adb install -r app\build\outputs\apk\stable\debug\app-stable-debug.apk
```

### Build the SDK-free monitor release

The monitor release omits the proprietary VITURE headers, native runtime, and
VITURE-only timing controls. It retains generic external-display modes and the
shared PhoneUI/BeastUI workspace. Build it with:

```powershell
.\gradlew.bat :app:assembleStableRelease "-Plateral.vitureSdk=false"
```

The resulting APK is `app\build\outputs\apk\stable\release\app-stable-release-unsigned.apk`.
It is unsigned unless a local release signing configuration is supplied.

### Build the optional VITURE variant

VITURE support is completely optional. If you want VITURE-specific display
timing and glasses controls, request access to the official SDK from
[VITURE's developer portal](https://www.viture.com/en-US/developer), download
it under VITURE's terms, and provision the private layout below. Do not commit
or redistribute the SDK files.

```powershell
.\gradlew.bat :app:assembleVitureRelease "-Plateral.vitureSdk=true"
```

This produces the green-logo `LATERAL_ (V)` APK. The VITURE variant requires
the SDK and fails at build time if its headers or ARM64 runtime are unavailable.

Then follow the first-use steps above. A source build still
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
project's `LICENSE` covers LATERAL_-specific source; `LICENSE-UXSPACE`,
`third_party/florisboard/LICENSE`, and `NOTICE` cover retained third-party
attribution.

## Troubleshooting

- **Embedded keyboard unavailable**: pair or restart the privileged helper and
  confirm Wireless debugging is enabled. LATERAL_ restores the previously
  selected Android keyboard rather than using shell text injection.
- **No embedded-keyboard haptics**: enable Android's system haptics. LATERAL_
  uses FlorisBoard's built-in key-press feedback, which follows that preference
  and vibrates the phone rather than the glasses.
- **BeastUI appears on the phone**: reconnect the glasses and relaunch
  LATERAL_; the external display must be available before BeastUI can attach.
- **Black hosted app card**: reconnect or minimize/restore the card to trigger
  surface reattachment.
- **Build cannot find `libglasses.so` or VITURE headers**: verify the private
  SDK layout above and that the SDK was obtained from VITURE directly.
