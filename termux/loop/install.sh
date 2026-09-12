#!/data/data/com.termux/files/usr/bin/bash
# Run inside Termux; no root or desktop SDK required.
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
INSTALL_DIR="${TERMUX_LOOP_HOME:-$HOME/termux-gradle-loop}"
SKIP_PACKAGES=0
ACCEPT_LICENSES=0
for arg in "$@"; do
  case "$arg" in
    --skip-packages) SKIP_PACKAGES=1 ;;
    --accept-sdk-licenses) ACCEPT_LICENSES=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
test -n "${PREFIX:-}" && test -d "$PREFIX" || { echo 'Run this installer in Termux.' >&2; exit 1; }
case "$(uname -m)" in aarch64|x86_64) ;; *) echo 'Supported target architectures: aarch64 and x86_64.' >&2; exit 1;; esac
if [ "$SKIP_PACKAGES" = 0 ]; then
  apt-get update
  apt-get -o Dpkg::Options::=--force-confold upgrade -y
  pkg install -y openjdk-17 aapt2 android-tools python curl unzip
fi
mkdir -p "$INSTALL_DIR/tools" "$INSTALL_DIR/downloads" "$INSTALL_DIR/android-sdk/cmdline-tools"
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export ANDROID_HOME="$INSTALL_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export TMPDIR="$PREFIX/tmp"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"
download() {
  if [ ! -s "$2" ]; then
    curl --fail --location --retry 3 "$1" -o "$2.part"
    mv "$2.part" "$2"
  fi
}
GRADLE_ZIP="$INSTALL_DIR/downloads/gradle-8.9-bin.zip"
download https://services.gradle.org/distributions/gradle-8.9-bin.zip "$GRADLE_ZIP"
download https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256 "$GRADLE_ZIP.sha256"
printf '%s  %s\n' "$(cat "$GRADLE_ZIP.sha256")" "$GRADLE_ZIP" | sha256sum -c -
if [ ! -x "$INSTALL_DIR/tools/gradle-8.9/bin/gradle" ]; then unzip -q "$GRADLE_ZIP" -d "$INSTALL_DIR/tools"; fi
CLI_ZIP="$INSTALL_DIR/downloads/commandlinetools-linux-11076708_latest.zip"
download https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip "$CLI_ZIP"
if [ ! -f "$ANDROID_HOME/cmdline-tools/12.0/bin/sdkmanager" ]; then
  unpack="$(mktemp -d "$INSTALL_DIR/downloads/sdk.XXXXXX")"
  unzip -q "$CLI_ZIP" -d "$unpack"
  mv "$unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/12.0"
  rmdir "$unpack"
fi
SDKMANAGER="$ANDROID_HOME/cmdline-tools/12.0/bin/sdkmanager"
if [ "$ACCEPT_LICENSES" = 1 ]; then
  # yes may receive SIGPIPE after sdkmanager exits; preserve sdkmanager's status.
  set +o pipefail
  yes | bash "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses
  set -o pipefail
else
  bash "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses
fi
bash "$SDKMANAGER" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'
for runtime in solve_loop.py demo_solver.py model_solver.py bridge.py make_demo.py export_phone_evidence.py PHONE_SETUP.md; do
  if [ "$(realpath "$SOURCE_DIR/$runtime")" != "$INSTALL_DIR/$runtime" ]; then cp "$SOURCE_DIR/$runtime" "$INSTALL_DIR/$runtime"; fi
done
if [ ! -d "$INSTALL_DIR/sample" ]; then cp -R "$SOURCE_DIR/sample" "$INSTALL_DIR/sample"; fi
if [ "$(realpath "$SOURCE_DIR/sample/gradlew")" != "$INSTALL_DIR/sample/gradlew" ]; then
  cp "$SOURCE_DIR/sample/gradlew" "$INSTALL_DIR/sample/gradlew"
fi
cat > "$INSTALL_DIR/env.sh" <<ENV
export TERMUX_LOOP_HOME="$INSTALL_DIR"
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$INSTALL_DIR/gradle-cache"
export TMPDIR="$PREFIX/tmp"
export PATH="$INSTALL_DIR/tools/gradle-8.9/bin:$JAVA_HOME/bin:$PREFIX/bin:\$PATH"
export GRADLE_OPTS="-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false"
ENV
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$INSTALL_DIR/sample/local.properties"
python - "$INSTALL_DIR/sample/gradle.properties" "$PREFIX/bin/aapt2" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
lines = [line for line in path.read_text().splitlines()
         if not line.startswith('android.aapt2FromMavenOverride=')]
path.write_text('\n'.join(lines) + '\nandroid.aapt2FromMavenOverride=' + sys.argv[2] + '\n')
PY
chmod +x "$INSTALL_DIR/sample/gradlew"
. "$INSTALL_DIR/env.sh"
java -version
aapt2 version
bash "$INSTALL_DIR/tools/gradle-8.9/bin/gradle" --version
dpkg-query -W > "$INSTALL_DIR/toolchain-packages.txt"
sha256sum "$GRADLE_ZIP" "$CLI_ZIP" "$PREFIX/bin/aapt2" > "$INSTALL_DIR/toolchain-sha256.txt"
printf '\nInstalled to %s\nRun: source "%s/env.sh"\n' "$INSTALL_DIR" "$INSTALL_DIR"
