#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
SOURCE_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AGENT_HOME="${LATERAL_AGENT_HOME:-$HOME/lateral-agent}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:/system/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
SKIP_PACKAGES=0
NO_START=0
INSTALL_ANDROID_SDK=0
ACCEPT_SDK_LICENSES=0
for argument in "$@"; do
  case "$argument" in
    --skip-packages) SKIP_PACKAGES=1;;
    --no-start) NO_START=1;;
    --android-sdk) INSTALL_ANDROID_SDK=1;;
    --accept-sdk-licenses) ACCEPT_SDK_LICENSES=1;;
    *) printf 'Unknown install argument: %s\n' "$argument" >&2; exit 2;;
  esac
done
if [ "$ACCEPT_SDK_LICENSES" = 1 ] && [ "$INSTALL_ANDROID_SDK" = 0 ]; then
  echo '--accept-sdk-licenses requires --android-sdk.' >&2
  exit 2
fi
test -x "$PREFIX/bin/bash" || { echo 'Install from the Termux terminal.' >&2; exit 1; }
if [ "$SKIP_PACKAGES" = 0 ]; then
  apt-get update
  apt-get -o Dpkg::Options::=--force-confold install -y python git curl ripgrep tmux ca-certificates android-tools
fi
for dependency in python git curl tar; do command -v "$dependency" >/dev/null; done
# A partially updated Termux can have incompatible libcurl/OpenSSL. Fail with a
# concrete remediation rather than silently upgrading the user's whole setup.
curl --version >/dev/null || { echo 'Termux libraries need updating: run pkg upgrade, then retry.' >&2; exit 1; }
mkdir -p "$AGENT_HOME/bin" "$AGENT_HOME/libexec" "$AGENT_HOME/state" "$AGENT_HOME/downloads" "$AGENT_HOME/projects/workspace"
case "$(uname -m)" in
  x86_64) ARCH=x86_64; DIGEST=f479424eca092484dc40d87ae28c44f4cc40234a60045d6131e493800d814a30;;
  aarch64) ARCH=aarch64; DIGEST=5cda6182bd94c3a30f2eb63a495489ebf7f691fddb14d70f48c6c1a5071b6cde;;
  *) echo 'Supported architectures: x86_64 and aarch64.' >&2; exit 1;;
esac
VERSION=0.153.4
ARCHIVE="$AGENT_HOME/downloads/codex-$VERSION-$ARCH.tar.gz"
URL="https://github.com/openai/codex/releases/download/rust-v$VERSION/codex-$ARCH-unknown-linux-musl.tar.gz"
if [ ! -f "$ARCHIVE" ]; then
  curl --fail --location --retry 3 "$URL" -o "$ARCHIVE.part"
  mv "$ARCHIVE.part" "$ARCHIVE"
fi
printf '%s  %s\n' "$DIGEST" "$ARCHIVE" | sha256sum -c -
EXTRACT="$(mktemp -d "$AGENT_HOME/downloads/unpack.XXXXXX")"
tar -xzf "$ARCHIVE" -C "$EXTRACT"
install -m 700 "$EXTRACT/codex-$ARCH-unknown-linux-musl" "$AGENT_HOME/libexec/codex.new"
mv "$AGENT_HOME/libexec/codex.new" "$AGENT_HOME/libexec/codex"
for runtime in agent_service.py codex_solver.py manage_service.py lateral-agent lateral-codex install.sh manifest.json; do
  destination="$AGENT_HOME/$runtime"
  case "$runtime" in lateral-agent|lateral-codex) destination="$AGENT_HOME/bin/$runtime";; esac
  if [ "$(realpath "$SOURCE_DIR/$runtime")" != "$destination" ]; then cp "$SOURCE_DIR/$runtime" "$destination"; fi
done
chmod 700 "$AGENT_HOME/bin/lateral-agent" "$AGENT_HOME/bin/lateral-codex"
chmod 700 "$AGENT_HOME/state"
if [ -f "$AGENT_HOME/state/bridge.token" ]; then chmod 600 "$AGENT_HOME/state/bridge.token"; fi
"$AGENT_HOME/bin/lateral-codex" --version
if [ "$INSTALL_ANDROID_SDK" = 1 ]; then
  test -f "$SOURCE_DIR/loop/install.sh" || { echo 'Bundled Gradle installer is missing.' >&2; exit 1; }
  sdk_arguments=()
  if [ "$ACCEPT_SDK_LICENSES" = 1 ]; then sdk_arguments+=(--accept-sdk-licenses); fi
  if [ -f "$HOME/termux-gradle-loop/env.sh" ] && command -v java >/dev/null && command -v aapt2 >/dev/null; then
    sdk_arguments+=(--skip-packages)
  fi
  bash "$SOURCE_DIR/loop/install.sh" "${sdk_arguments[@]}"
fi
python - "$AGENT_HOME" "$ARCH" "$VERSION" "$DIGEST" <<'PY'
import json, pathlib, sys
home = pathlib.Path(sys.argv[1])
(home / 'state/install.json').write_text(json.dumps({'runtimeVersion': 1,
    'codexVersion': sys.argv[3], 'architecture': sys.argv[2],
    'archiveSha256': sys.argv[4], 'mode': 'native-musl'}, indent=2) + '\n')
PY
if [ "$NO_START" = 0 ]; then "$AGENT_HOME/bin/lateral-agent" start; fi
printf 'LATERAL Agent installed at %s\n' "$AGENT_HOME"
