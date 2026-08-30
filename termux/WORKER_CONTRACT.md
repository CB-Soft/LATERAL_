# Termux/Box64 Gradle worker contract

**Protocol version:** `1`
**Reference implementation:** [`gradle-worker.sh`](gradle-worker.sh)

This contract describes a deliberately small build boundary for running the
LATERAL_ Android build from Termux, including a Box64-backed environment. The
worker is a build runner, not a general shell or command broker.

## Request

The invoking adapter supplies these explicit arguments:

```text
--workspace <absolute path>       required; LATERAL_ repository root
--artifact-dir <absolute path>   required; existing directory under workspace
--variant <dev|stable>           optional; defaults to dev
--request-id <opaque id>          optional; echoed in the result
```

The reference worker accepts only two named variants: `dev`, mapped to
`:app:assembleDevDebug`, and `stable`, mapped to `:app:assembleStableDebug`.
It does not accept arbitrary Gradle tasks, extra Gradle flags, shell snippets,
or a working directory inferred from the caller's current directory. A request
is rejected before the build when paths are missing, relative, inaccessible, or
outside the workspace/artifact boundary.

The equivalent logical request object is:

```json
{
  "protocolVersion": 1,
  "requestId": "example-2026-08-29T120000Z",
  "workspace": "/data/data/com.termux/files/home/src/LATERAL_",
  "artifactDirectory": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts",
  "variant": "dev",
  "task": ":app:assembleDevDebug"
}
```

`requestId` is correlation metadata only. It must not be interpreted as a path
or command. `workspace` must contain the repository's `gradlew` wrapper and
`app/` project. The caller creates `artifactDirectory` before invoking the
worker; the worker creates only a fresh `run-<timestamp>-<pid>` child within it.

## Result

The worker prints one JSON result object to stdout and writes the same object to
`result.json` in the unique run directory. Gradle output is captured in the
same directory as `gradle.log`.

Successful result:

```json
{
  "protocolVersion": 1,
  "requestId": "example-2026-08-29T120000Z",
  "status": "succeeded",
  "variant": "dev",
  "task": ":app:assembleDevDebug",
  "workspace": "/data/data/com.termux/files/home/src/LATERAL_",
  "artifactDirectory": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts",
  "runDirectory": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts/run-20260829T120000Z-4127",
  "artifact": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts/run-20260829T120000Z-4127/app-debug.apk",
  "log": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts/run-20260829T120000Z-4127/gradle.log",
  "exitCode": 0,
  "failure": null
}
```

Failed result:

```json
{
  "protocolVersion": 1,
  "requestId": "example-2026-08-29T120000Z",
  "status": "failed",
  "variant": "dev",
  "task": ":app:assembleDevDebug",
  "workspace": "/data/data/com.termux/files/home/src/LATERAL_",
  "artifactDirectory": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts",
  "runDirectory": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts/run-20260829T120000Z-4127",
  "artifact": null,
  "log": "/data/data/com.termux/files/home/src/LATERAL_/termux/artifacts/run-20260829T120000Z-4127/gradle.log",
  "exitCode": 1,
  "failure": {
    "kind": "gradle-failed",
    "message": "The approved Gradle task exited non-zero; inspect gradle.log."
  }
}
```

`failure.kind` is one of:

- `gradle-failed`: the fixed Gradle invocation returned non-zero;
- `missing-artifact`: Gradle returned zero but the expected debug APK was not
  produced;
- `worker-error`: reserved for a worker-side failure after a run directory is
  available.

The process exit status is `0` only for `status: "succeeded"`; it is non-zero
for a failed build or missing artifact. Consumers should use the JSON status and
failure object for diagnostics rather than parsing human-readable Gradle text.

## Safety rules

- Never invoke `eval`, `sh -c`, a caller-supplied command, or arbitrary Gradle
  arguments.
- Never run `clean`, delete a build directory, or overwrite a prior run.
- Execute the repository's `gradlew` only after resolving and validating the
  explicit workspace path.
- Keep artifacts and logs in a caller-created directory below the workspace.
- Use a unique run directory so retries are additive and auditable.
- Treat the APK as an output artifact; installing it, uploading it, or running
  ADB commands is outside this contract.
- Do not put SDK secrets, pairing data, or private signing material in the
  request or result object.
