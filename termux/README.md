# Termux/Box64 build worker

This directory contains the v0.2 delegated-build seam for a Termux environment,
including Termux setups that execute the JVM/Gradle workload through Box64.
The worker is intentionally limited to the repository's debug APK build. It is
not part of the Android app runtime and is not wired into Gradle as a custom
task.

Read the [worker contract](WORKER_CONTRACT.md) before integrating an adapter.

## Prerequisites

- Termux with a working `sh`, `cat`, `date`, `mkdir`, `cp`, `sed`, and `pwd`.
- A Java/Gradle environment that can execute the repository wrapper. Box64 may
  provide the compatibility layer, but the worker does not install or configure
  it.
- The repository's private VITURE SDK layout, when required by the selected
  source build. Do not pass SDK paths or secrets through the request object.
- An existing artifact directory inside the checkout, for example:

  ```sh
  mkdir -p termux/artifacts
  ```

## Invocation

Run from any directory with explicit absolute paths:

```sh
sh termux/gradle-worker.sh \
  --workspace "$PWD" \
  --artifact-dir "$PWD/termux/artifacts" \
  --request-id "phone-build-$(date -u +%Y%m%dT%H%M%SZ)"
```

The wrapper itself is invoked with fixed, conservative flags. The default
development invocation is:

```text
./gradlew --no-daemon --console=plain --stacktrace :app:assembleDevDebug
```

Only the named `dev` and `stable` variants are accepted; they map to fixed
Gradle tasks. No arbitrary command, task, cleanup operation, or extra Gradle
argument can be supplied through the worker CLI. Each invocation creates a new
`termux/artifacts/run-<timestamp>-<pid>/` directory containing:

- `result.json` — structured success/failure result;
- `gradle.log` — complete Gradle stdout/stderr capture;
- `app-debug.apk` — copied only when the build succeeds and the expected APK
  exists.

The worker also prints `result.json` to stdout for an orchestrator. A failed
Gradle process or a successful build without the expected APK is a failed
worker result and a non-zero process exit.

## Fake-worker test guidance

The worker can be tested without Android SDK, Box64, or the VITURE SDK by using
a disposable fake repository. The fake `gradlew` must create the expected APK
on success and return a chosen status on failure. Keep the fixture outside the
real checkout or in a temporary directory; do not point the test at the real
`termux/artifacts` directory if you want to verify no existing artifacts are
modified.

Successful fake build (POSIX shell):

```sh
fixture="$(mktemp -d)"
mkdir -p "$fixture/app/build/outputs/apk/debug" "$fixture/termux/artifacts"
cat > "$fixture/gradlew" <<'FAKE_GRADLEW'
#!/system/bin/sh
set -eu
printf 'fake gradlew args:'
printf ' <%s>' "$@"
printf '\n'
printf 'fake apk\n' > app/build/outputs/apk/debug/app-debug.apk
FAKE_GRADLEW
chmod +x "$fixture/gradlew"

sh termux/gradle-worker.sh \
  --workspace "$fixture" \
  --artifact-dir "$fixture/termux/artifacts" \
  --request-id fake-success
test "$?" -eq 0
test -f "$fixture/termux/artifacts"/run-*/result.json
test -f "$fixture/termux/artifacts"/run-*/app-debug.apk
```

Failure fake build:

```sh
fixture="$(mktemp -d)"
mkdir -p "$fixture/app" "$fixture/termux/artifacts"
cat > "$fixture/gradlew" <<'FAKE_GRADLEW'
#!/system/bin/sh
printf 'intentional fake failure\n' >&2
exit 17
FAKE_GRADLEW
chmod +x "$fixture/gradlew"

if sh termux/gradle-worker.sh \
  --workspace "$fixture" \
  --artifact-dir "$fixture/termux/artifacts" \
  --request-id fake-failure
then
  echo 'expected the worker to fail' >&2
  exit 1
fi
```

Adapter tests should additionally assert that relative paths, missing artifact
directories, paths outside the workspace, unknown flags, and an attempted
alternate task are rejected before the fake wrapper is called. Retry tests
should assert that two runs have different run directories and that the first
run's log/result remain unchanged.
