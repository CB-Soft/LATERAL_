# Managed Termux Agent runtime

This bundle installs the official Codex CLI as the only coding-agent harness.
The local service and LATERAL dev UI manage projects, settings and status; coding
requests execute through Codex CLI. The existing `termux/loop` toolchain remains
separate and is sourced when present.

## APK bootstrap contract

Run `python termux/agent/package.py` from the repository to rebuild
`app/src/dev/assets/agent-runtime.tar.gz`. The asset contains source files at its
archive root plus per-file SHA256 checksums. It contains no Codex binaries,
credentials or device state and is limited to 150 KB for Android Binder transport.
The dev app sends its bundled bytes through the supported Termux RUN_COMMAND
stdin mechanism, verifies the archive, extracts into `~/lateral-agent/source`,
writes its private token at `~/lateral-agent/state/bridge.token` mode 0600, then
runs `bash ~/lateral-agent/source/install.sh`. No public repository deployment is
required. Termux permissions and `allow-external-apps` must be enabled by the user.

The installer obtains Python, Git, curl, ripgrep, tmux, ADB and certificates from the
Termux package repository, then downloads the official Codex CLI release pinned
in `manifest.json`. It verifies the embedded architecture-specific SHA256 before
extracting. Full Termux package upgrades are not automatic; incompatible curl
libraries produce an actionable error. `--skip-packages` reuses installed packages;
`--no-start` installs without starting the service. Existing projects, Codex
authentication and service settings are preserved.

The default managed installation installs only the Agent base and Codex CLI.
The asset also bundles the existing Gradle-loop source installer and sample for
an explicit Android-tools installation. In the foreground Termux terminal, run:

```sh
~/lateral-agent/bin/lateral-agent install --android-sdk
```

The SDK manager displays its license prompts for the user to review. Only add
`--accept-sdk-licenses` when the user explicitly opts into unattended acceptance;
that flag requires `--android-sdk`. Existing native Java/AAPT2 installations are
reused. A clean Gradle setup installs its prerequisites, SDK and pinned Gradle
through the bundled installer; no prepopulated emulator cache is required.
Downloads require network access. The default background setup accepts no SDK
licenses and does not install Android build tools silently.

## Installed commands

- `~/lateral-agent/bin/lateral-agent start [--serial ADB_SERIAL]`: start the
  loopback service at port 8766 and return after readiness. An authenticated
  already-running instance is reused.
- `lateral-agent terminal`: open an interactive Termux shell in the default
  workspace, using a named tmux session when available.
- `lateral-agent login`: run `codex login --device-auth` in the user's terminal.
- `lateral-agent codex ...` or `~/lateral-agent/bin/lateral-codex ...`: run the
  official binary with the native Termux environment and optional Gradle setup.
- `lateral-agent doctor`: print installed versions and Codex login status, then
  probe Linux sandbox support, returning failure if the sandbox is unavailable.
- `lateral-agent install`: rerun the installed installer.

The full command paths work before opening the managed terminal. That terminal
adds the runtime bin directory to PATH. Codex credentials stay in its normal
private configuration home. The service token never appears in URLs. Service
output is at `~/lateral-agent/state/service.log`.

## Platform and verification scope

Pinned Codex version: **0.153.4**, official OpenAI Linux musl release. x86_64 and
ARM64 URLs and SHA256 values are recorded in the manifest. The x86_64 binary has
passed `--version` and `exec --help` natively in the emulator. Native ARM64 Codex
execution is not yet verified. No proot fallback is silently selected.
The emulator's native Linux sandbox probe currently fails because the required
isolation is unavailable; a legacy Landlock probe also failed. The service keeps
workspace-write mode as its default and fails closed. Successful CLI startup
does not override this limitation or enable unrestricted execution.
Android/Termux is an integration target here, not an official platform-support
claim. Codex sandbox behavior, authentication and actual agent execution need
their own runtime validation; binary startup alone does not prove them.

Termux packages come from a rolling repository; this is a pinned Codex binary
and repeatable installation recipe, not a complete offline base-system snapshot.
Model configuration belongs to Codex. Any local-provider mode still executes
through Codex CLI, not a parallel ad-hoc model harness.

Official documentation: [Codex CLI](https://learn.chatgpt.com/docs/codex/cli)
and [authentication](https://learn.chatgpt.com/docs/auth).
The device-code flow may require enabling device-code login in account settings.
