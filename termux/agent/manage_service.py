#!/usr/bin/env python3
"""Start the private local agent service, returning once its TCP listener is ready."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request


def healthy(home):
    token = home / 'state/bridge.token'
    if not token.is_file():
        return False
    request = urllib.request.Request('http://127.0.0.1:8766/v1/health',
        headers={'Authorization': 'Bearer ' + token.read_text().strip()})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status == 200
    except (OSError, ValueError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, required=True)
    parser.add_argument('--serial')
    args = parser.parse_args()
    home = args.home.resolve(strict=True)
    if healthy(home):
        print('LATERAL Agent service is already running at http://127.0.0.1:8766')
        return 0
    state = home / 'state'
    state.mkdir(mode=0o700, exist_ok=True)
    command = [sys.executable, '-u', str(home / 'agent_service.py'),
        '--home', str(home), '--token-file', str(state / 'bridge.token'),
        '--codex-command', json.dumps([str(home / 'bin/lateral-codex')]),
        '--loop-home', str(Path.home() / 'termux-gradle-loop'), '--port', '8766']
    if args.serial:
        command.extend(['--serial', args.serial])
    with (state / 'service.log').open('ab') as log:
        process = subprocess.Popen(command, cwd=home, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (state / 'service.pid').write_text(str(process.pid) + '\n')
    for _ in range(60):
        if process.poll() is not None:
            print('Agent service exited; see ' + str(state / 'service.log'), file=sys.stderr)
            return 1
        try:
            with socket.create_connection(('127.0.0.1', 8766), timeout=0.2):
                if healthy(home):
                    print('LATERAL Agent service ready at http://127.0.0.1:8766')
                    return 0
        except OSError:
            pass
        time.sleep(0.2)
    print('Agent service did not become ready; see ' + str(state / 'service.log'), file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
