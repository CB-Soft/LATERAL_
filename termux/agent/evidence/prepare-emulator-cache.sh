#!/data/data/com.termux/files/usr/bin/bash
set -eu
mkdir -p "$HOME/lateral-agent/downloads"
cp "$HOME/lateral-agent-probe/codex.tar.gz" "$HOME/lateral-agent/downloads/codex-0.153.4-x86_64.tar.gz"
# This is the documented one-time Termux setting; APK installation uses RUN_COMMAND.
mkdir -p "$HOME/.termux"
python - <<'PY'
from pathlib import Path
p = Path.home() / '.termux/termux.properties'
lines = p.read_text().splitlines() if p.exists() else []
lines = [x for x in lines if not x.strip().startswith('allow-external-apps=')]
p.write_text('\n'.join(lines + ['allow-external-apps=true']) + '\n')
PY
termux-reload-settings
