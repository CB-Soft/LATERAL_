#!/system/bin/sh
# Conservative Termux/Box64 worker for the LATERAL_ debug APK.
#
# Deliberate limits:
#   * explicit absolute workspace and artifact paths are required;
#   * only the explicit dev/stable debug tasks are accepted;
#   * no caller-supplied shell or Gradle arguments are evaluated;
#   * no clean/delete/overwrite operation is performed.

set -u

approved_task=':app:assembleDevDebug'
approved_variant='dev'
workspace=''
artifact_dir=''
request_id=''

usage() {
    printf '%s\n' 'Usage: gradle-worker.sh --workspace ABS_PATH --artifact-dir ABS_PATH [--variant dev|stable] [--request-id ID]' >&2
}

fail_usage() {
    printf 'worker request rejected: %s\n' "$1" >&2
    usage
    exit 2
}

is_absolute() {
    case "$1" in
        /*) return 0 ;;
        *) return 1 ;;
    esac
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --workspace)
            [ "$#" -ge 2 ] || fail_usage 'missing --workspace value'
            workspace=$2
            shift 2
            ;;
        --artifact-dir)
            [ "$#" -ge 2 ] || fail_usage 'missing --artifact-dir value'
            artifact_dir=$2
            shift 2
            ;;
        --request-id)
            [ "$#" -ge 2 ] || fail_usage 'missing --request-id value'
            request_id=$2
            shift 2
            ;;
        --variant)
            [ "$#" -ge 2 ] || fail_usage 'missing --variant value'
            case "$2" in
                dev)
                    approved_variant='dev'
                    approved_task=':app:assembleDevDebug'
                    ;;
                stable)
                    approved_variant='stable'
                    approved_task=':app:assembleStableDebug'
                    ;;
                *)
                    fail_usage '--variant must be dev or stable'
                    ;;
            esac
            shift 2
            ;;
        --task|--gradle-arg|--command|--clean)
            fail_usage "$1 is not supported"
            ;;
        *)
            fail_usage "unknown argument: $1"
            ;;
    esac
done

[ -n "$workspace" ] || fail_usage '--workspace is required'
[ -n "$artifact_dir" ] || fail_usage '--artifact-dir is required'
is_absolute "$workspace" || fail_usage '--workspace must be absolute'
is_absolute "$artifact_dir" || fail_usage '--artifact-dir must be absolute'
case "$request_id" in
    *[!A-Za-z0-9._:-]*) fail_usage '--request-id may contain only letters, digits, dot, underscore, colon, and hyphen' ;;
esac

workspace=$(CDPATH= cd "$workspace" 2>/dev/null && pwd -P) || fail_usage 'workspace is not accessible'
[ "$workspace" != '/' ] || fail_usage 'workspace may not be filesystem root'
[ -f "$workspace/gradlew" ] || fail_usage 'workspace/gradlew is missing'
[ -f "$workspace/app" ] && fail_usage 'workspace/app must be a directory'
[ -d "$workspace/app" ] || fail_usage 'workspace/app is missing'

[ -d "$artifact_dir" ] || fail_usage 'artifact directory must already exist'
artifact_dir=$(CDPATH= cd "$artifact_dir" 2>/dev/null && pwd -P) || fail_usage 'artifact directory is not accessible'
case "$artifact_dir" in
    "$workspace"/*) ;;
    *) fail_usage 'artifact directory must be below workspace' ;;
esac

timestamp=$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null) || timestamp='unknown-time'
run_dir="$artifact_dir/run-$timestamp-$$"
suffix=0
while :; do
    if mkdir "$run_dir" 2>/dev/null; then
        break
    fi
    suffix=$((suffix + 1))
    [ "$suffix" -le 99 ] || fail_usage 'could not allocate a unique run directory'
    run_dir="$artifact_dir/run-$timestamp-$$-$suffix"
done

log_file="$run_dir/gradle.log"
result_file="$run_dir/result.json"
apk_source="$workspace/app/build/outputs/apk/$approved_variant/debug/app-$approved_variant-debug.apk"
apk_result="$run_dir/app-debug.apk"

# Do not use `eval` here. The wrapper path and every Gradle argument are fixed.
# Run from the repository root so Gradle and fake wrappers resolve relative
# project paths consistently, regardless of the caller's current directory.
(
    cd "$workspace" || exit 125
    sh ./gradlew --no-daemon --console=plain --stacktrace "$approved_task"
) >"$log_file" 2>&1
build_exit=$?

status='failed'
artifact_json='null'
failure_kind='gradle-failed'
failure_message='The approved Gradle task exited non-zero; inspect gradle.log.'
result_exit=$build_exit

if [ "$build_exit" -eq 125 ]; then
    failure_kind='worker-error'
    failure_message='The worker could not enter the validated workspace; inspect gradle.log.'
    result_exit=125
elif [ "$build_exit" -eq 0 ]; then
    if [ -f "$apk_source" ]; then
        if cp -p "$apk_source" "$apk_result"; then
            status='succeeded'
            artifact_json="\"$apk_result\""
            failure_kind=''
            failure_message=''
            result_exit=0
        else
            failure_kind='worker-error'
            failure_message='The worker could not copy the completed APK into the fresh run directory.'
            result_exit=1
        fi
    else
        failure_kind='missing-artifact'
        failure_message='Gradle succeeded but the expected debug APK was not produced.'
        result_exit=1
    fi
fi

json_escape() {
    # All generated values are paths/IDs supplied as single shell arguments;
    # escape the JSON metacharacters needed for those values.
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

workspace_json=$(json_escape "$workspace")
artifact_dir_json=$(json_escape "$artifact_dir")
run_dir_json=$(json_escape "$run_dir")
log_file_json=$(json_escape "$log_file")
request_id_json=$(json_escape "$request_id")

if [ "$status" = 'succeeded' ]; then
    failure_json='null'
else
    failure_kind_json=$(json_escape "$failure_kind")
    failure_message_json=$(json_escape "$failure_message")
    failure_json="{\"kind\":\"$failure_kind_json\",\"message\":\"$failure_message_json\"}"
fi

result_json=$(cat <<EOF
{
  "protocolVersion": 1,
  "requestId": "${request_id_json}",
  "status": "${status}",
  "variant": "${approved_variant}",
  "task": "${approved_task}",
  "workspace": "${workspace_json}",
  "artifactDirectory": "${artifact_dir_json}",
  "runDirectory": "${run_dir_json}",
  "artifact": ${artifact_json},
  "log": "${log_file_json}",
  "exitCode": ${result_exit},
  "failure": ${failure_json}
}
EOF
)

printf '%s\n' "$result_json" > "$result_file"
printf '%s\n' "$result_json"
exit "$result_exit"
