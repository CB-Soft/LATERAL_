# Termux Gradle solving loop

This source package builds an Android APK with Gradle inside Termux, installs it
through an authorized ADB connection, launches it, collects logcat and UI evidence,
repairs source, and retries within an attempt limit. The included sample starts
with a deliberate runtime exception. Success requires the installed app process
and the visible text `TERMUX LOOP SOLVED`; compiling alone is not success.

`demo_solver.py` performs a deterministic one-line repair of the known fixture.
`model_solver.py` optionally asks a separately configured model to repair files.
The deterministic demo is not an autonomous AI agent. Android runs the build,
installation, observation, and retry orchestration; the model can run elsewhere.

## Install on another device

For a normal, nondebuggable Termux installation, follow [physical-phone setup](PHONE_SETUP.md).
It covers terminal installation, Termux's separate ADB pairing, and exporting
one run's evidence through Downloads without `run-as`.

Use Termux from its [official installation sources](https://github.com/termux/termux-app#installation).
Do not mix Termux and add-ons from differently signed sources. This installer
targets Android's `aarch64` and `x86_64` architectures; physical-device validation
must be recorded separately from emulator validation. Android 11 or newer offers
the simplest standalone wireless-debugging setup. Allow several GB of free space,
internet access for packages/Maven/SDK downloads, and time for a first build.

On the computer, create the distributable with Python 3:

```sh
python termux/loop/package.py
```

Copy `dist/termux-gradle-loop.tar.gz` and its `.sha256` file to the target. In Termux,
grant storage access using `termux-setup-storage` if copying through Downloads,
then extract into private home storage (shared storage cannot execute build tools):

```sh
cd ~/storage/downloads
sha256sum -c termux-gradle-loop.tar.gz.sha256
tar -xzf termux-gradle-loop.tar.gz -C "$HOME"
cd "$HOME/termux-gradle-loop"
sha256sum -c SHA256SUMS
bash install.sh
source "$HOME/termux-gradle-loop/env.sh"
```

The installer shows Google's SDK license prompts. `--accept-sdk-licenses` is an
explicit unattended option for users who already agree to those terms.
`--skip-packages` is only for a device with the prerequisites already installed.
Set `TERMUX_LOOP_HOME` before installation to choose a different private path.
Reinstallation preserves application source and repairs, and updates the sample's
toolchain launcher and native AAPT2 configuration.

The installer obtains Termux-native Java 17, AAPT2, Python and ADB through the
Termux package manager. It downloads Gradle 8.9 and checks its official SHA256;
downloads Google's command-line tools revision 11076708 (12.0); and uses the Java
SDK manager to install Android platform 35/build-tools 35.0.0. AGP is pinned to
8.7.3. The SDK archive uses HTTPS but does not yet have an embedded independent
checksum; package versions from the rolling Termux repository are not locked.
This is a repeatable installation recipe, not a fully offline or bit-identical
toolchain snapshot. Maven/SDK caches are deliberately excluded from the package.

The project overrides AGP's desktop AAPT2 with the native Termux binary. Gradle's
native services and filesystem watching are disabled. The included Java-only
sample does not require an NDK. Projects using native desktop build helpers,
CMake, or an NDK need additional architecture-specific work.

## Authorize ADB and run

On Android 11+, enable Developer options / Wireless debugging. In Termux, use the
pairing address and pairing code shown under **Pair device with pairing code**,
then connect to the separate debugging address on the main wireless-debugging page:

```sh
adb pair DEVICE_IP:PAIRING_PORT
adb connect DEVICE_IP:DEBUGGING_PORT
adb devices
source "$HOME/termux-gradle-loop/env.sh"
python "$TERMUX_LOOP_HOME/solve_loop.py" \
  --workspace "$TERMUX_LOOP_HOME/sample" \
  --config "$TERMUX_LOOP_HOME/sample/loop.json" \
  --serial DEVICE_IP:DEBUGGING_PORT --max-attempts 3 --demo-solver
```

The ports differ and can change after reconnecting. Use the exact authorized
serial shown by `adb devices`. On an emulator managed from a computer, the host
can enable TCP ADB using `adb -s emulator-5554 tcpip 5555`; Termux can then use
`adb connect 127.0.0.1:5555` and serial `127.0.0.1:5555`. TCP ADB configuration
and host-side bootstrap are separate from the Gradle execution inside Termux.

The tested emulator already exposed its local ADB endpoint, so no transport change
was needed. From Windows, the repository's `scripts/install-termux-loop.ps1`
can install the archive with `-Serial emulator-5554 -AcceptSdkLicenses`; it requires
a debuggable Termux APK supporting `run-as`. Ordinary phones use the Termux steps
above. The helper does not alter ADB settings.

Keep the screen unlocked during UI verification. The first attempt should compile,
install and crash; the adapter changes `BROKEN = true` to `false`; the second
attempt rebuilds and checks the resulting screen. Each run retains diagnostics
under the workspace. Restore the source marker to `true` to repeat the initial
failure; do not overwrite another project's changes.

To repeat the demo without modifying an earlier workspace, run:

```sh
python "$TERMUX_LOOP_HOME/make_demo.py" --run --serial DEVICE_IP:DEBUGGING_PORT
```

This creates a fresh workspace under `$TERMUX_LOOP_HOME/projects/` and prints its
path. The runner executes trusted operator-configured argument arrays, not a
general-purpose sandbox for untrusted Gradle projects.

## Optional model repair

The model endpoint must implement OpenAI-compatible chat completions with
JSON Schema structured output and be
reachable from Termux. Set explicit endpoint/model variables, keep credentials
out of project files, and choose the files the model may edit:

```sh
export MODEL_BASE_URL=http://127.0.0.1:1234/v1
export MODEL_NAME=your-loaded-model
# export MODEL_API_KEY=...  # only if your endpoint requires authentication
python - <<'PY'
import json, os
from pathlib import Path
root = Path(os.environ['TERMUX_LOOP_HOME'])
p = root / 'sample/loop-model.json'
config = json.loads((root / 'sample/loop.json').read_text())
config['solverCommand'] = ['python', str(root / 'model_solver.py'), '--file',
    'app/src/main/java/com/example/termuxloop/MainActivity.java']
p.write_text(json.dumps(config, indent=2))
PY
python "$TERMUX_LOOP_HOME/solve_loop.py" \
  --workspace "$TERMUX_LOOP_HOME/sample" \
  --config "$TERMUX_LOOP_HOME/sample/loop-model.json" \
  --serial DEVICE_IP:DEBUGGING_PORT --max-attempts 3
```

For a fresh sample containing both a missing semicolon and the launch defect:

```sh
python "$TERMUX_LOOP_HOME/make_demo.py" --model --compile-error --run \
  --serial DEVICE_IP:DEBUGGING_PORT
```

The adapter requests JSON output, allows one formatting/truncation retry, and
keeps rejected responses locally for diagnosis. `MODEL_MAX_TOKENS` defaults to
4096. The sample and its saved evidence are separate for each generated workspace.

For a computer-hosted LM Studio model, configure host ADB reverse separately:
`adb -s emulator-5554 reverse tcp:1234 tcp:1234`. With this arrangement, model
inference runs on the computer; Gradle and the loop run on Android. The adapter
sends allowlisted source and bounded failure diagnostics to the endpoint,
validates returned edits, and saves originals before replacement. Model output
does not become an arbitrary shell command. Provider availability and a model's
ability to repair other bugs are not guaranteed by the known sample.

## PhoneUI bridge

```sh
python "$TERMUX_LOOP_HOME/bridge.py" \
  --workspace "$TERMUX_LOOP_HOME/sample" \
  --config "$TERMUX_LOOP_HOME/sample/loop.json" \
  --serial DEVICE_IP:DEBUGGING_PORT --demo-solver
```

In the LATERAL_ **dev** build, paste the printed token-bearing localhost URL into PhoneUI's bridge setting,
connect, approve the configured project, and start the job. The bridge launches
the fixed configured project. Freeform objectives do not select shell commands.
Live preview integration is not included. The standalone sample APK is launched
on the device display, and the runner captures its verification evidence.

## Upstream references

- [Termux native AAPT tools](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh)
- [Termux Java 17](https://github.com/termux/termux-packages/blob/master/packages/openjdk-17/build.sh)
- [AGP 8.7 compatibility requirements](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
- [Android SDK manager and licenses](https://developer.android.com/tools/sdkmanager)
- [Android wireless ADB pairing](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)
- [Gradle distributions](https://services.gradle.org/distributions/)
