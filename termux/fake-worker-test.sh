#!/usr/bin/env sh
# Contract smoke tests. These use a fake Gradle wrapper and never build or
# install an APK; they verify the worker's allowlist and artifact handoff.
set -eu

worker_dir=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/lateral-worker-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

workspace="$test_root/workspace"
artifact_dir="$workspace/artifacts"
mkdir -p "$workspace/app" "$artifact_dir"

cat >"$workspace/gradlew" <<'EOF'
#!/usr/bin/env sh
set -eu
case "$*" in
    *:app:assembleDevDebug)
        mkdir -p app/build/outputs/apk/dev/debug
        printf 'fake-dev-apk\n' > app/build/outputs/apk/dev/debug/app-dev-debug.apk
        ;;
    *:app:assembleStableDebug)
        mkdir -p app/build/outputs/apk/stable/debug
        printf 'fake-stable-apk\n' > app/build/outputs/apk/stable/debug/app-stable-debug.apk
        ;;
    *) exit 9 ;;
esac
EOF

result=$(sh "$worker_dir/gradle-worker.sh" \
    --workspace "$workspace" \
    --artifact-dir "$artifact_dir" \
    --variant dev \
    --request-id fake-1)

printf '%s\n' "$result" | grep -F '"status": "succeeded"' >/dev/null
printf '%s\n' "$result" | grep -F '"task": ":app:assembleDevDebug"' >/dev/null
find "$artifact_dir" -name app-debug.apk -type f -print -quit | grep . >/dev/null

if sh "$worker_dir/gradle-worker.sh" \
    --workspace "$workspace" \
    --artifact-dir "$artifact_dir" \
    --command 'echo forbidden'; then
    printf '%s\n' 'worker accepted a forbidden command' >&2
    exit 1
fi

printf '%s\n' 'fake worker contract tests passed'
