# Ordinary Termux on a physical phone

These commands run in the installed Termux terminal. They do not require a
debuggable APK, root, `run-as`, or replacement of Termux. Use the existing verified
source archive and its sibling SHA256 file. Keep the phone unlocked for permission
dialogs, ADB pairing, and UI verification.

## Transfer and install

The computer can copy the archive into Android's shared Downloads folder using
its already authorized ADB connection. This does not grant the computer access
to Termux's private home:

```powershell
adb -s PHONE_SERIAL push termux/loop/dist/termux-gradle-loop.tar.gz /sdcard/Download/
adb -s PHONE_SERIAL push termux/loop/dist/termux-gradle-loop.tar.gz.sha256 /sdcard/Download/
adb -s PHONE_SERIAL push termux/loop/export_phone_evidence.py /sdcard/Download/
```

Open Termux and run `termux-setup-storage`, allowing the requested storage access.
Verify and extract the archive into a new source directory to preserve an existing
installation's projects:

```sh
termux-setup-storage
cd "$HOME/storage/downloads"
sha256sum -c termux-gradle-loop.tar.gz.sha256
source_dir="$(mktemp -d "$HOME/termux-loop-source.XXXXXX")"
tar -xzf termux-gradle-loop.tar.gz -C "$source_dir"
cd "$source_dir/termux-gradle-loop"
sha256sum -c SHA256SUMS
bash install.sh
source "$HOME/termux-gradle-loop/env.sh"
```

Read and accept the SDK licenses when prompted. The explicit installer flag
`--accept-sdk-licenses` supports a user who already agrees to those terms.
Installation upgrades the Termux packages first to prevent mismatched libraries.
The extracted installer defaults to `$HOME/termux-gradle-loop`. The source archive
contains no built SDK caches, so the first installation requires downloads.

## Pair Termux's own ADB client

The computer's wireless-debugging authorization does **not** authorize Termux's
separate ADB key. On the phone, open Developer options → Wireless debugging →
Pair device with pairing code. Use split screen with Termux if switching apps
closes the pairing dialog on this device.

Run these inside Termux, using the IP and pairing port shown in that dialog:

```sh
adb pair PHONE_IP:PAIRING_PORT
```

Enter the displayed six-digit code. Return to the main Wireless debugging page
and use its **different** connection port:

```sh
adb connect PHONE_IP:DEBUGGING_PORT
adb devices -l
```

Use the serial printed by this Termux `adb devices` command for the loop. Do not
assume the computer's mDNS serial or pairing port is the right loop target.
If Android changes the connection port, reconnect with its new value.

## Run a fresh validation project

```sh
source "$HOME/termux-gradle-loop/env.sh"
python "$TERMUX_LOOP_HOME/make_demo.py" --run --serial PHONE_IP:DEBUGGING_PORT
```

This keeps the installed sample intact, prints the new workspace path, then runs
the known-crash fixture and deterministic repair. Success should require two
attempts and a visible `TERMUX LOOP SOLVED` screen. A physical-device result is
only confirmed after its own runner reports success; emulator results do not
establish ARM64 compatibility.

For a separately configured model, use `--model --run` and set `MODEL_BASE_URL`
and `MODEL_NAME` in this same terminal. A computer endpoint is not reachable at
the emulator-only `10.0.2.2` address from a physical phone. An authorized computer
can instead run `adb -s PHONE_SERIAL reverse tcp:1234 tcp:1234`; Termux can then
use `MODEL_BASE_URL=http://127.0.0.1:1234/v1`. Model inference remains on the
computer in that configuration. Existing model configuration and authentication
must match the endpoint.

## Export one completed run without run-as

The runner prints its run directory. Substitute that exact directory below:

```sh
python "$TERMUX_LOOP_HOME/export_phone_evidence.py" \
  --run-dir "$TERMUX_LOOP_HOME/projects/demo-ID/termux-loop-runs/run-ID"
```

The helper writes a uniquely named `.tar.gz` and `.sha256` into Downloads and
prints the archive path. It includes only that run's result and attempt log,
screenshot, and XML files. It excludes source files, source backups, APKs,
`context.json`, toolchain caches, environment files, and ADB keys. Logs and
screenshots can still contain app data; inspect them before sharing beyond this
test. The script does not send data over the network or delete the originals.

If testing an older archive that predates the helper, copy it separately using
the transfer command above, then invoke
`python "$HOME/storage/downloads/export_phone_evidence.py"` with the same arguments.

The computer can retrieve the printed filenames:

```powershell
adb -s PHONE_SERIAL pull /sdcard/Download/termux-loop-evidence-ID.tar.gz .
adb -s PHONE_SERIAL pull /sdcard/Download/termux-loop-evidence-ID.tar.gz.sha256 .
```

Keep the archive checksum with the evidence. Files have per-entry hashes in the
archive's `EXPORT.json`. Export after the run finishes so files are no longer
being updated.
