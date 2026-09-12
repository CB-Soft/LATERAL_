# Physical phone acceptance — 2026-09-08

The build/install/observe/repair loop passed on the physical nubia NX789J.
Gradle, Java, native AAPT2, Python orchestration and the paired ADB client ran
inside ordinary Termux. Model inference ran in LM Studio on the computer using
`google/gemma-4-26b-a4b`, reached through ADB reverse at `127.0.0.1:1234`.

## Verified device and installation

- Device: nubia NX789J, ARM64, Android 15.
- Termux: 0.118.3, ordinary nondebuggable installation.
- Installation ran through the normal Termux terminal UI. It did not use
  `run-as` or replace Termux with a debuggable APK.
- Termux's own ADB client was paired using Android's wireless-debugging pairing
  dialog, then connected to `10.0.0.10:38929` for this session. The port is
  session-specific and is not a fixed installation setting.
- Native toolchain: Java 17.0.20, AAPT2 2.20 from Android 16 revision 4,
  Gradle 8.9, and Android SDK 35.

The initial archive installed the native toolchain. Its SHA256 was:
`9afe8c07397567080706a61f6dbf91b6a504c715bdb5fb44ad483161613962e7`.

The solving runs used archive
`cce1f04cbe6f9e10a7f53a5ba554e800ffce2ffa574f63334dd369be0928402c`,
including the phone guide and evidence exporter, installed with `--skip-packages`.
Archive and packaged-file checksums were verified. The final package adds
bounded local HTTP error diagnostics and two regression tests; its SHA256 is:
`01d1dbec5b873b369cf92b00b922696b6e2aa3fd93002012ec4b2d2f47466677`.

[Checksum sidecar](dist/termux-gradle-loop.tar.gz.sha256) ·
[Ordinary-phone setup](PHONE_SETUP.md)

## Proven solving runs

- **Deterministic crash repair: two attempts.** Cold build 1m32s; repaired build
  17s. The first launch showed Android's “Termux Loop Demo has stopped” dialog;
  package-scoped UI verification correctly failed. The adapter disabled the
  deliberate crash and the second attempt passed. The crash buffer was empty on
  this phone; the initial failure is evidenced by the system dialog in `ui.log`.
  [Result](evidence/nubia/run-20260908T223049-85c1a478/result.json) ·
  [Initial dialog](evidence/nubia/run-20260908T223049-85c1a478/attempt-01/ui.log) ·
  [Working screen](evidence/nubia/run-20260908T223049-85c1a478/attempt-02/screenshot.png).
- **Model repair: three attempts.** The fresh fixture had a missing semicolon
  and the intentional crash. The first replacement introduced an illegal
  backslash, which the second compilation caught. The next model replacement
  built, installed and passed verification. Build times: 15s, 13s, 16s.
  No manual source repair was inserted into this run.
  [Result](evidence/nubia/run-20260908T223807-100d5c4b/result.json) ·
  [Working screen](evidence/nubia/run-20260908T223807-100d5c4b/attempt-03/screenshot.png).

Both successes required installed APK hash equality, a running target process,
package-scoped `TERMUX LOOP SOLVED` text, a valid screenshot and no reported target
crash during the observation interval. Both screenshots were visually inspected.
Archive checksums and every exported evidence file's SHA256 were verified.

An earlier model run stopped on HTTP 400 before editing source. The same request
later succeeded; the cause was not established. Its
[failed result](evidence/nubia/run-20260908T223317-b6b97d8a/result.json) is retained.

## Tests and scope

All 32 tests in the solving-run package passed inside Termux with no skips.
The final diagnostic update passed 33 tests on Windows with one POSIX-only skip.
**All 34 tests in the final archive passed inside the nubia's Termux, with no
skips**, after checksum verification and reinstallation. See the
[final installation/test log](evidence/nubia/test.log).

This verifies the Java-only sample on physical ARM64, not the full native
LATERAL_ application. The runner uses display 0; virtual-display preview remains
unimplemented. Private projects and toolchains remain installed under
`~/termux-gradle-loop`. The successful model workspace is
`projects/demo-5d728aa96f03`.
The original 60-second screen timeout was restored after testing. Termux's
pairing and the localhost model reverse connection remain available; wireless
debugging ports can change after reconnecting.

The [emulator acceptance report](VERIFIED.md) records separate successful
deterministic and model-assisted solving runs on x86_64. Those runs are historical
emulator evidence, separate from these physical-phone runs.
