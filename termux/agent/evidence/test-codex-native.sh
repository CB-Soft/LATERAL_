#!/data/data/com.termux/files/usr/bin/bash
set -eu
export SHELL="$PREFIX/bin/bash"
mkdir -p "$HOME/lateral-agent-probe/workspace"
printf 'Replace this text with NATIVE_CODEX_OK\n' > "$HOME/lateral-agent-probe/workspace/probe.txt"
exec "$HOME/lateral-agent-probe/codex-x86_64-unknown-linux-musl" -a never -c 'web_search="disabled"' exec --json --skip-git-repo-check --sandbox workspace-write -C "$HOME/lateral-agent-probe/workspace" --oss --local-provider lmstudio -m google/gemma-4-26b-a4b -- 'Read probe.txt with a shell command, then replace its entire contents with NATIVE_CODEX_OK followed by a newline. Verify the file with a shell command. Do not just describe the change.'
