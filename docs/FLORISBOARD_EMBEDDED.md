# Embedded FlorisBoard maintenance

LATERAL_ pins FlorisBoard v0.5.2 in `third_party/florisboard` and consumes its
isolated `:embedded` Android library through a Gradle included build. The module
reuses upstream keyboard sources, resources, layouts, native engine, and Snygg
styling rather than copying them into the LATERAL_ app.

The fork's integration surface is intentionally small:

- `embedded/` defines the Android-library target and session-only IME manifest.
- `EmbeddedImeRuntime` exposes editor metadata and the Android View/Compose facade;
  it never exposes or logs text, composition, clipboard data, or passwords.
- `FlorisImeService` supports a non-rendering system input view and initializes its
  editor state from `onStartInput` even when Android never shows that view.
- `EmbeddedKeyboardTheme` accepts a LATERAL_-owned Snygg stylesheet compatible with
  `FlorisImeUiSpec`. There is no embedded theme editor, import UI, or user-controlled
  arbitrary stylesheet surface.

## Session runtime behavior

LATERAL_ passively binds the app-owned Floris IME service during application
startup and precomposes the hidden PhoneUI keyboard view. This only warms
classes, resources, engine state, and the Compose tree: it does **not** select
FlorisBoard as the system IME, request an `InputConnection`, show an IME window,
or transfer focus from a hosted app. The normal session controller still selects
FlorisBoard only after an exact Beast editor is identified and restores the prior
IME when that session ends.

Embedded keyboard presses use FlorisBoard's upstream `InputFeedbackController`.
The normal key-press setting remains enabled, and its system-feedback snapshot is
refreshed at embedded-session start because this mode intentionally never shows
Android's IME window. Feedback therefore follows Android's system haptics toggle
and drives the phone vibrator only.

## Build prerequisites

Install JDK 17, the Android SDK/NDK versions requested by Gradle, CMake 4.1.2, and
the stable Rust toolchain. The fork builds only `arm64-v8a`, matching the Nubia
NX789J. CMake invokes `rustup target add aarch64-linux-android` automatically.

After a recursive clone:

```powershell
git submodule update --init --recursive
cd third_party\florisboard
.\gradlew.bat :app:assembleDebug :embedded:assembleDebug
cd ..\..
.\gradlew.bat :app:assembleDebug
```

## Upgrade workflow

1. Fetch the maintained FlorisBoard fork and create an integration branch from the
   desired upstream release tag.
2. Rebase the small LATERAL_ integration commit stack onto that tag. Resolve changes
   only inside the `:embedded` target, runtime facade, application host hook, and
   session-mode IME hook.
3. Build FlorisBoard's upstream `:app` target to catch source/resource regressions.
4. Build `:embedded:assembleDebug` and its tests.
5. Build and test LATERAL_, including IME activation, identity verification,
   previous-IME restoration, process death, and Standard/Ultrawide transitions.
6. Advance the parent repository's submodule pointer only after the fork commit is
   published and recursively cloneable.

Do not edit generated FlorisBoard source in the parent repository and do not expose
the standalone FlorisBoard settings, theme-import, provider, voice-switching, or
launcher surfaces through LATERAL_.
