# Emulator acceptance — 2026-09-08

The build/install/observe/repair loop passed on `emulator-5554` (x86_64).
Gradle, Java, AAPT2, Python orchestration and the ADB client ran inside Termux.
The model-assisted test used `google/gemma-4-26b-a4b` in LM Studio on the host
computer, reached from the emulator at `http://10.0.2.2:1234/v1`.

## Proven runs

- **Crash repair: 2 attempts.** The first APK built successfully, installed and
  crashed with `IllegalStateException: TERMUX_LOOP_DEMO_BUG`. The deterministic
  adapter repaired the fixture. The second APK built, installed, launched and
  passed verification. Cold build: 4m49s; rebuild: 55s.
  [Result](evidence/baseline/termux-loop-runs/run-20260908T203715-f1237e19/result.json)
  · [Crash](evidence/baseline/termux-loop-runs/run-20260908T203715-f1237e19/attempt-01/crash.log)
  · [Working screen](evidence/baseline/termux-loop-runs/run-20260908T203715-f1237e19/attempt-02/screenshot.png).
- **Real model repair: 3 attempts.** A fresh sample contained a missing semicolon
  and the launch defect. Attempt one failed compilation. The model's first
  replacement contained an incomplete import, which attempt two caught. The
  model's next replacement compiled on attempt three, installed, launched and
  passed verification. Build times: 45s, 49s and 56s. All repairs were performed
  by the configured adapter; no manual source repair was inserted into this run.
  [Result](evidence/model/termux-loop-runs/run-20260908T211932-cb9b8316/result.json)
  · [Working screen](evidence/model/termux-loop-runs/run-20260908T211932-cb9b8316/attempt-03/screenshot.png)
  · [Final source](evidence/model/app/src/main/java/com/example/termuxloop/MainActivity.java).

Verification required a successful Gradle process, installed APK hash equality,
a running target process, package-scoped UI text `TERMUX LOOP SOLVED`, a valid
screenshot and no target crash in the attempt's log interval. Both successful
screenshots were also visually inspected.

Earlier model-adapter integration attempts rejected response formatting before
editing source. They remain in the exported model evidence. The final adapter
uses JSON Schema output, bounded retries and file allowlists, and keeps backups.

## Installation and tests

The emulator acceptance archive was installed through
[`install-termux-loop.ps1`](../../scripts/install-termux-loop.ps1) with
`-Serial emulator-5554 -SkipPackages -AcceptSdkLicenses`. The helper verified the
archive SHA256 and every packaged file. Prerequisites were previously installed
in Termux using the same package operations; reinstall preserved repaired source.
Java 17.0.20, native AAPT2, Gradle 8.9, AGP 8.7.3 and SDK/build-tools 35 were used.

**All 29 tests in that emulator archive passed inside Termux.** This includes actual
descendant-process cancellation, failure handling, APK identity, model edit
validation and local PhoneUI bridge protocol tests. Windows passed 28 tests and
skipped the POSIX-only cancellation test.

- [Portable archive](dist/termux-gradle-loop.tar.gz)
- [SHA256 sidecar](dist/termux-gradle-loop.tar.gz.sha256)
- [Installation and usage](README.md)

Historical emulator acceptance archive SHA256:
`9afe8c07397567080706a61f6dbf91b6a504c715bdb5fb44ad483161613962e7`

The current linked archive adds ordinary-phone installation documentation,
the phone evidence exporter, bounded model HTTP diagnostics and five tests.
Its SHA256 is:
`01d1dbec5b873b369cf92b00b922696b6e2aa3fd93002012ec4b2d2f47466677`.
It was installed on the physical ARM64 nubia phone, with checksums verified and
**all 34 packaged tests passing inside its Termux, without skips**. Physical-phone
crash repair and model-assisted repair also passed; see [phone acceptance](PHONE_VERIFIED.md).

## Scope

The emulator installation is at `/data/data/com.termux/files/home/termux-gradle-loop`.
The repaired model project is in its `projects/demo-b26c94c7cd22` directory.
The sample app was left running on the emulator after verification.

This verifies a Java-only sample, not the complete native LATERAL_ application.
The physical ARM64 nubia NX789J passed installation, all 34 packaged tests,
and both sample solving cases. Rolling
Termux package versions and SDK downloads require network
access. The pinned SDK manager emits an XML-version warning with newer repository
metadata; installation completed successfully.

The PhoneUI-compatible bridge is included and protocol-tested. A PhoneUI live
preview and virtual-display isolation are **not implemented** here: the runner
uses display 0 and explicitly rejects secondary-display verification.

Evidence exports are local deliverables and are excluded from the installer
archive and Git by default. Use `scripts/export-termux-loop-evidence.py` from the
repository root to export subsequent runs.
