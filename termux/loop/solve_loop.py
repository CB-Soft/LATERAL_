#!/usr/bin/env python3
"""Bounded, evidence-producing Android build/install/repair loop. Python stdlib only."""
import argparse
import hashlib
import json
import os
import re
import signal
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def argv(value, name):
    if not isinstance(value, list) or not value or any(not isinstance(x, str) or not x or '\0' in x for x in value):
        raise ValueError(name + ' must be a nonempty JSON array of argument strings')
    return value


def inside(workspace, relative):
    path = (workspace / relative).resolve()
    if path == workspace or workspace not in path.parents:
        raise ValueError('Path must be below workspace: ' + str(relative))
    return path


def execute(command, cwd, output, timeout, binary=False):
    """Never interpret shell syntax. All commands and diagnostics are recorded."""
    try:
        with output.open('wb') as stream:
            process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT if not binary else subprocess.DEVNULL,
                                       shell=False, start_new_session=(os.name != 'nt'))
            try:
                process.wait(timeout=timeout)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                # Termux is POSIX: kill the process group, including Gradle's child JVM.
                try:
                    if os.name != 'nt':
                        os.killpg(process.pid, signal.SIGKILL)
                    else:
                        process.kill()
                except ProcessLookupError:
                    pass  # Child finished between cancellation and group termination.
                process.wait()
                raise
        return {'command': command, 'exitCode': process.returncode, 'log': str(output)}
    except subprocess.TimeoutExpired:
        return {'command': command, 'exitCode': 124, 'log': str(output), 'error': 'command timed out'}
    except OSError as error:
        output.write_text(str(error), encoding='utf-8')
        return {'command': command, 'exitCode': 127, 'log': str(output), 'error': str(error)}


def verify_ui(path, package, expected):
    try:
        nodes = list(ET.parse(path).iter('node'))
        return any(n.get('package') == package and expected in (n.get('text', '') + '\n' + n.get('content-desc', '')) for n in nodes)
    except (OSError, ET.ParseError):
        return False


def run_loop(*args, **kwargs):
    state = {}
    try:
        return _run_loop(*args, **kwargs, state=state)
    except KeyboardInterrupt:
        summary = state.get('summary')
        if summary:
            summary['status'] = 'cancelled'
            summary['stopReason'] = 'cancelled'
            if summary['attempts']:
                summary['attempts'][-1]['status'] = 'cancelled'
                summary['attempts'][-1]['failure'] = 'cancelled'
            write_json(Path(summary['runDirectory']) / 'result.json', summary)
            print(json.dumps({'status': 'cancelled', 'runDirectory': summary['runDirectory']}), flush=True)
        raise


def _run_loop(workspace, config, serial, max_attempts=3, adb='adb', demo_solver=False, display=0, executor=execute, sleeper=time.sleep, state=None):
    workspace = Path(workspace).resolve(strict=True)
    if not workspace.is_dir() or not serial or serial.startswith('-') or any(c.isspace() for c in serial):
        raise ValueError('An existing workspace and explicit adb serial are required')
    if not 1 <= max_attempts <= 20:
        raise ValueError('max-attempts must be 1..20')
    if display != 0:
        raise ValueError('UI verification currently requires display 0; secondary displays are not supported')
    build = argv(config['buildCommand'], 'buildCommand')
    apk = inside(workspace, config['apk'])
    package, activity, expected = config['package'], config['activity'], config['expectedText']
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+', package):
        raise ValueError('Invalid Android package')
    if not re.fullmatch(r'[A-Za-z0-9_.$]+', activity) or not isinstance(expected, str) or not expected:
        raise ValueError('Activity and nonempty expectedText required')
    solver = config.get('solverCommand')
    if demo_solver:
        solver = [sys.executable, str(Path(__file__).with_name('demo_solver.py'))]
    if solver is not None:
        argv(solver, 'solverCommand')
    timeout = float(config.get('commandTimeoutSeconds', 600))
    settle = float(config.get('settleSeconds', 2))
    if not 1 <= timeout <= 3600 or not 0 <= settle <= 60:
        raise ValueError('timeout must be 1..3600 seconds; settle must be 0..60 seconds')
    root = inside(workspace, config.get('artifactDirectory', 'termux-loop-runs'))
    root.mkdir(parents=True, exist_ok=True)
    run = root / ('run-' + time.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
    run.mkdir()
    summary = {'protocolVersion': 1, 'status': 'failed', 'workspace': str(workspace), 'serial': serial,
               'solverMode': 'deterministic-demo' if demo_solver else ('external-command' if solver else 'none'),
               'runDirectory': str(run), 'attempts': []}
    state['summary'] = summary
    write_json(run / 'result.json', summary)
    for number in range(1, max_attempts + 1):
        attempt_dir = run / ('attempt-%02d' % number)
        attempt_dir.mkdir()
        attempt = {'number': number, 'status': 'failed', 'steps': [], 'failure': None}
        summary['attempts'].append(attempt)

        def command(name, args, binary=False):
            print(json.dumps({'attempt': number, 'stage': name, 'status': 'running'}), flush=True)
            result = executor(args, workspace, attempt_dir / (name + ('.png' if binary else '.log')), timeout, binary=binary)
            result['name'] = name
            attempt['steps'].append(result)
            return result

        def device(name, args, binary=False):
            return command(name, [adb, '-s', serial] + args, binary)

        def fail(reason):
            if attempt['failure'] is None:
                attempt['failure'] = reason

        if command('build', build)['exitCode'] != 0:
            fail('build-failed')
        elif not apk.is_file():
            fail('missing-apk')
        else:
            archived_apk = attempt_dir / 'app.apk'
            shutil.copy2(apk, archived_apk)
            attempt['apk'] = str(archived_apk)
            attempt['apkSha256'] = hashlib.sha256(archived_apk.read_bytes()).hexdigest()
            if device('device', ['get-state'])['exitCode'] != 0:
                fail('device-unavailable')
            elif device('install', ['install', '-r', str(archived_apk)])['exitCode'] != 0:
                fail('install-failed')
            else:
                installed = device('installed-package', ['shell', 'pm', 'path', package])
                paths = Path(installed['log']).read_text().splitlines()
                bases = [p.removeprefix('package:') for p in paths if p.startswith('package:') and p.endswith('/base.apk')]
                if installed['exitCode'] != 0 or len(bases) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', bases[0]):
                    fail('installed-package-unverified')
                else:
                    digest = device('installed-apk-hash', ['shell', 'sha256sum', bases[0]])
                    tokens = Path(digest['log']).read_text().split()
                    if digest['exitCode'] != 0 or not tokens or tokens[0] != attempt['apkSha256']:
                        fail('installed-apk-mismatch')
                timestamp = device('start-time', ['shell', 'date', '+%m-%dT%H:%M:%S.%N'])
                start_time = Path(timestamp['log']).read_text().strip().replace('T', ' ')
                if timestamp['exitCode'] != 0 or not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3,9}', start_time):
                    fail('device-time-unavailable')
                if device('stop', ['shell', 'am', 'force-stop', package])['exitCode'] != 0:
                    fail('stop-failed')
                if attempt['failure'] is None:
                    launched = device('launch', ['shell', 'am', 'start', '-W', '--display', '0', '-n', package + '/' + activity])
                    launch_text = Path(launched['log']).read_text(encoding='utf-8', errors='replace')
                    if launched['exitCode'] != 0 or re.search(r'(^|\n)(Error|Exception)', launch_text):
                        fail('launch-failed')
                    sleeper(settle)
                    pid = device('process', ['shell', 'pidof', package])
                    if pid['exitCode'] != 0 or not re.fullmatch(r'\d+(\s+\d+)*', Path(pid['log']).read_text().strip()):
                        fail('process-not-running')
                    remote_ui = '/sdcard/termux-loop-' + run.name + '-' + str(number) + '.xml'
                    dump = device('ui-dump', ['shell', 'uiautomator', 'dump', remote_ui])
                    ui = device('ui', ['exec-out', 'cat', remote_ui])
                    if dump['exitCode'] != 0 or ui['exitCode'] != 0 or not verify_ui(Path(ui['log']), package, expected):
                        fail('ui-verification-failed')
                    screenshot = device('screenshot', ['exec-out', 'screencap', '-p'], binary=True)
                    if screenshot['exitCode'] != 0 or not Path(screenshot['log']).read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
                        fail('screenshot-failed')
                    device('remove-ui', ['shell', 'rm', '-f', remote_ui])
                if attempt['failure'] != 'device-time-unavailable':
                    logs = device('logcat', ['logcat', '-d', '-v', 'threadtime', '-T', start_time])
                    crash = device('crash', ['logcat', '-b', 'crash', '-d', '-v', 'brief', '-T', start_time])
                    if logs['exitCode'] != 0 or crash['exitCode'] != 0:
                        fail('logcat-capture-failed')
                    if package in Path(crash['log']).read_text(encoding='utf-8', errors='replace'):
                        fail('application-crashed')
        if attempt['failure'] is None:
            attempt['status'] = 'succeeded'
            summary['status'] = 'succeeded'
        context = {'protocolVersion': 1, 'workspace': str(workspace), 'package': package, 'expectedText': expected,
                   'attempt': attempt, 'remainingAttempts': max_attempts - number}
        context_path = attempt_dir / 'context.json'
        write_json(context_path, context)
        write_json(run / 'result.json', summary)
        print(json.dumps({'attempt': number, 'status': attempt['status'], 'failure': attempt['failure'], 'context': str(context_path)}), flush=True)
        if summary['status'] == 'succeeded' or number == max_attempts or not solver:
            break
        repair = command('solver', solver + [str(context_path)])
        write_json(run / 'result.json', summary)
        if repair['exitCode'] != 0:
            summary['stopReason'] = 'solver-failed'
            break
    write_json(run / 'result.json', summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--display', type=int, default=0)
    parser.add_argument('--max-attempts', type=int, default=3)
    parser.add_argument('--demo-solver', action='store_true', help='Use deterministic sample repair (not an AI solver)')
    args = parser.parse_args()
    def terminate(signum, frame):
        # Bridge cancellation uses SIGTERM. Convert it to the same cleanup path as Ctrl-C.
        raise KeyboardInterrupt
    previous_termination_handler = signal.signal(signal.SIGTERM, terminate)
    try:
        config = json.loads(Path(args.config).read_text(encoding='utf-8'))
        result = run_loop(args.workspace, config, args.serial, args.max_attempts, args.adb, args.demo_solver, args.display)
        print(json.dumps(result, indent=2))
        return 0 if result['status'] == 'succeeded' else 1
    except (ValueError, KeyError, OSError) as error:
        print('loop configuration/error: ' + str(error), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print('Loop cancelled; active process group terminated.', file=sys.stderr)
        return 130
    finally:
        signal.signal(signal.SIGTERM, previous_termination_handler)


if __name__ == '__main__':
    sys.exit(main())
