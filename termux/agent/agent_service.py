#!/usr/bin/env python3
"""Authenticated local Codex CLI harness. No model-provider HTTP API calls."""
import argparse
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

CAPABILITIES = {'project.files.read': 'READ', 'project.files.write': 'WRITE',
                'project.build': 'ACTION', 'device.logs.read': 'READ',
                'workspace.screenshot': 'READ', 'android.install.debug': 'PRIVILEGED'}


def save(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2), encoding='utf-8')
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def codex_argv(command, settings, project, objective):
    result = list(command) + ['-a', 'never', 'exec', '--json', '--skip-git-repo-check',
                              '--sandbox', 'workspace-write', '-C', str(project)]
    if settings.get('codexMode') == 'oss-lmstudio':
        result += ['--oss', '--local-provider', 'lmstudio', '-m', settings['model']]
        # Hosted web search is not a local LM Studio function tool.
        result += ['-c', 'web_search="disabled"']
        if settings.get('modelCatalogPath'):
            result += ['-c', 'model_catalog_json=' + json.dumps(settings['modelCatalogPath'])]
    return result + ['--', objective]


def codex_environment(settings, environment=None):
    """Built-in OSS endpoint override is an environment variable, not a provider redefinition."""
    result = dict(os.environ if environment is None else environment)
    result.pop('CODEX_OSS_BASE_URL', None)
    if settings.get('codexMode') == 'oss-lmstudio' and settings.get('lmstudioBaseUrl'):
        result['CODEX_OSS_BASE_URL'] = settings['lmstudioBaseUrl'].rstrip('/')
    return result


def write_local_catalog(path, model):
    """Codex0.153.4 ModelInfo: omit unsupported freeform tools, retain sandboxed exec.

    Schema: openai/codex rust-v0.153.4 codex-rs/protocol/src/openai_models.rs.
    Registration: core/src/tools/spec_plan.rs checks apply_patch_tool_type.is_some().
    """
    entry = {'slug': model, 'display_name': model, 'description': 'Local LM Studio function-tool profile',
             'supported_reasoning_levels': [], 'default_reasoning_level': None,
             'shell_type': 'unified_exec', 'visibility': 'list', 'supported_in_api': True, 'priority': 0,
             'availability_nux': None, 'upgrade': None,
             'base_instructions': 'You are a coding assistant. Use the provided exec_command function to inspect and edit project files. Respect the configured workspace and permissions. Complete the user objective and report verified results.',
             'supports_reasoning_summary_parameter': False, 'support_verbosity': False,
             'default_verbosity': None, 'apply_patch_tool_type': None,
             'truncation_policy': {'mode': 'bytes', 'limit': 10000},
             'experimental_supported_tools': [], 'input_modalities': ['text'],
             'supports_search_tool': False, 'tool_mode': 'direct', 'include_apps_usage_instructions': False}
    save(Path(path), {'models': [entry]})
    return str(Path(path))


def process_identity(pid):
    try:
        return (Path('/proc') / str(pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    except (OSError, IndexError):
        return None


class Harness:
    def __init__(self, home, codex_command, loop_home=None, serial=None, probe_run=subprocess.run):
        self.home = Path(home).resolve()
        self.home.mkdir(parents=True, exist_ok=True)
        os.chmod(self.home, 0o700)
        self.projects = self.home / 'projects'
        self.state = self.home / 'state'
        self.projects.mkdir(exist_ok=True)
        self.state.mkdir(exist_ok=True)
        os.chmod(self.state, 0o700)
        (self.projects / 'workspace').mkdir(exist_ok=True)
        self.codex_command = codex_command
        self.loop_home = Path(loop_home or self.home.parent / 'termux-gradle-loop').resolve()
        self.probe_run = probe_run
        self.lock = threading.RLock()
        self.processes = {}
        self.settings = {'codexMode': 'chatgpt', 'adbSerial': serial}
        if (self.state / 'settings.json').exists():
            self.settings.update(json.loads((self.state / 'settings.json').read_text()))
        if serial:
            self.settings['adbSerial'] = serial
        save(self.state / 'settings.json', self.settings)
        self.jobs = {}
        if (self.state / 'jobs.json').exists():
            self.jobs = json.loads((self.state / 'jobs.json').read_text())
        for job in self.jobs.values():
            if job['status'] in ('RUNNING', 'CANCELLING'):
                pid = job.get('pid')
                if pid and job.get('processIdentity') and process_identity(pid) == job['processIdentity']:
                    try:
                        os.killpg(pid, signal.SIGTERM)
                        deadline = time.monotonic() + 2
                        while process_identity(pid) == job['processIdentity'] and time.monotonic() < deadline:
                            time.sleep(.05)
                        if process_identity(pid) == job['processIdentity']:
                            os.killpg(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                job['status'] = 'FAILED'
                job['error'] = 'Service restarted during this job; run was interrupted and was not resumed'
                self.event(job, job['error'], 'ERROR')
        self.persist()

    def persist(self):
        save(self.state / 'jobs.json', self.jobs)

    def event(self, job, message, kind='STATUS'):
        with self.lock:
            sequence = job.get('lastSequence', 0) + 1
            job['lastSequence'] = sequence
            job['updatedAt'] = int(time.time() * 1000)
            job['events'].append({'sequence': sequence, 'at': job['updatedAt'], 'kind': kind, 'message': str(message)[:8192]})
            job['events'] = job['events'][-1000:]
            self.persist()

    def probe(self, command):
        try:
            result = self.probe_run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, timeout=3, shell=False)
            return result.returncode, result.stdout[:4096]
        except (OSError, subprocess.TimeoutExpired):
            return 127, ''

    def devices(self):
        code, output = self.probe(['adb', 'devices'])
        devices = []
        if code == 0:
            for line in output.splitlines():
                parts = line.split()
                if len(parts) >= 2 and parts[0] != 'List' and parts[1] in ('device', 'offline', 'unauthorized'):
                    devices.append({'serial': parts[0], 'status': parts[1]})
        return devices

    def health(self):
        code, version = self.probe(self.codex_command + ['--version'])
        login_code, _ = self.probe(self.codex_command + ['login', 'status']) if code == 0 else (127, '')
        auth = 'unavailable' if code else ('ready' if login_code == 0 else 'needs-login')
        devices = self.devices()
        selected = self.settings.get('adbSerial')
        selected_state = next((d['status'] for d in devices if d['serial'] == selected), 'not-selected' if not selected else 'disconnected')
        return {'ok': True, 'providerId': 'codex-cli', 'codex': {'version': version.strip(), 'authStatus': auth,
                'mode': self.settings['codexMode'], 'ready': code == 0 and (auth == 'ready' or self.settings['codexMode'] == 'oss-lmstudio')},
                'adb': {'serial': selected, 'status': selected_state, 'devices': devices},
                'projectsRoot': str(self.projects), 'defaultProject': str(self.projects / 'workspace'),
                'settings': self.settings}

    def project(self, value):
        path = Path(value)
        if not path.is_absolute():
            raise ValueError('Project path must be absolute')
        resolved = path.resolve(strict=True)
        if self.projects not in resolved.parents or not resolved.is_dir():
            raise ValueError('Project must be under the managed private projects directory')
        current = path
        while current != self.projects:
            if current.is_symlink():
                raise ValueError('Symlink projects are not supported')
            current = current.parent
            if current == current.parent:
                raise ValueError('Invalid project path')
        return resolved

    def create_project(self, name):
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', name):
            raise ValueError('Project name must contain letters, digits, hyphen, or underscore')
        path = self.projects / name
        path.mkdir(exist_ok=False)
        return {'name': name, 'projectRoot': str(path)}

    def update_settings(self, values):
        if set(values) - {'adbSerial', 'codexMode', 'model', 'lmstudioBaseUrl'}:
            raise ValueError('Unknown setting; executable and permission policy cannot be changed through jobs')
        with self.lock:
            if self.busy():
                raise ValueError('Wait for the current job before changing settings')
            updated = dict(self.settings, **values)
            if 'adbSerial' in values and values['adbSerial'] is not None:
                if not any(d['serial'] == values['adbSerial'] and d['status'] == 'device' for d in self.devices()):
                    raise ValueError('Select a connected authorized ADB serial')
            if updated['codexMode'] not in ('chatgpt', 'oss-lmstudio'):
                raise ValueError('codexMode must be chatgpt or oss-lmstudio')
            if updated['codexMode'] == 'oss-lmstudio' and (not isinstance(updated.get('model'), str) or not updated['model'].strip()):
                raise ValueError('An explicit local model is required')
            if updated.get('lmstudioBaseUrl'):
                url = urlsplit(updated['lmstudioBaseUrl'])
                if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
                    raise ValueError('Invalid LM Studio base URL')
            self.settings = updated
            save(self.state / 'settings.json', updated)
            return updated

    def busy(self):
        return any(j['status'] in ('RUNNING', 'CANCELLING') for j in self.jobs.values()) or any(p.poll() is None for p in self.processes.values())

    def execution_settings(self):
        settings = dict(self.settings)
        if settings['codexMode'] == 'oss-lmstudio':
            settings['modelCatalogPath'] = write_local_catalog(self.state / 'local-model-catalog.json', settings['model'])
        return settings

    def start(self, request, demo=False):
        allowed = {'jobId', 'objective', 'projectRoot', 'grantedCapabilities'}
        if set(request) - allowed:
            raise ValueError('Job cannot supply executable, model, settings, or extra permissions')
        identifier = request.get('jobId', '')
        if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', identifier):
            raise ValueError('Invalid jobId')
        grants = request.get('grantedCapabilities', [])
        if not isinstance(grants, list) or any(not isinstance(g, str) for g in grants) or set(grants) != set(CAPABILITIES):
            raise ValueError('Approve the fixed project/build/install/observation capabilities; unsupported grants are rejected')
        objective = request.get('objective', '')
        if not demo and (not isinstance(objective, str) or not objective.strip() or len(objective) > 16384):
            raise ValueError('Provide an objective of 1..16384 characters')
        with self.lock:
            if identifier in self.jobs or self.busy():
                raise ValueError('Job ID already used or another job is active')
            health = self.health()
            if not health['codex']['ready']:
                raise ValueError('Codex needs login: run codex login in Termux' if health['codex']['authStatus'] == 'needs-login' else 'Codex CLI is unavailable')
            if demo and health['adb']['status'] != 'device':
                raise ValueError('Select a connected authorized ADB serial before running a demo')
            project = self.make_demo() if demo else self.project(request['projectRoot'])
            now = int(time.time() * 1000)
            job = {'jobId': identifier, 'objective': objective or 'Repair and verify the Android demo',
                   'projectRoot': str(project), 'mode': 'solve-loop' if demo else 'codex',
                   'status': 'RUNNING', 'events': [], 'error': None, 'createdAt': now, 'updatedAt': now,
                   'settings': self.execution_settings(), 'grantedCapabilities': list(grants)}
            self.jobs[identifier] = job
            self.event(job, 'Starting Codex CLI' if not demo else 'Starting Gradle loop with Codex CLI repairs')
            threading.Thread(target=self.run, args=(job,), daemon=True).start()
            return {'jobId': identifier, 'status': 'RUNNING', 'projectRoot': str(project)}

    def make_demo(self):
        project = self.projects / ('demo-' + uuid.uuid4().hex[:12])
        sample = self.loop_home / 'sample'
        names = ['settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'loop.json', 'local.properties',
                 'app/build.gradle', 'app/src/main/AndroidManifest.xml', 'app/src/main/java/com/example/termuxloop/MainActivity.java']
        project.mkdir()
        for name in names:
            source = sample / name
            target = project / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        source = project / names[-1]
        text, count = re.subn(r'private static final boolean BROKEN = (?:true|false);', 'private static final boolean BROKEN = true;', source.read_text())
        if count != 1:
            raise ValueError('Bundled demo marker missing')
        source.write_text(text.replace('label.setTextSize(28);', 'label.setTextSize(28)'))
        config = json.loads((project / 'loop.json').read_text())
        config['solverCommand'] = [sys.executable, str(Path(__file__).with_name('codex_solver.py')),
                                  '--command-json', json.dumps(self.codex_command), '--settings-json', json.dumps(self.execution_settings())]
        save(project / 'loop.json', config)
        return project

    def run(self, job):
        process = None
        try:
            with self.lock:
                if job['status'] != 'RUNNING':
                    return
                if job['mode'] == 'solve-loop':
                    command = [sys.executable, '-u', str(self.loop_home / 'solve_loop.py'), '--workspace', job['projectRoot'],
                               '--config', str(Path(job['projectRoot']) / 'loop.json'), '--serial', job['settings']['adbSerial'], '--max-attempts', '3']
                else:
                    command = codex_argv(self.codex_command, job['settings'], job['projectRoot'], job['objective'])
                process = subprocess.Popen(command, cwd=job['projectRoot'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           text=True, encoding='utf-8', errors='replace', start_new_session=(os.name != 'nt'),
                                           env=codex_environment(job['settings']))
                self.processes[job['jobId']] = process
                job['pid'], job['processIdentity'] = process.pid, process_identity(process.pid)
                self.persist()
            completed = False
            failed_event = False
            timer = threading.Timer(1800, lambda: self.cancel(job['jobId'], 'Job exceeded the 30 minute limit'))
            timer.daemon = True
            timer.start()
            try:
                for line in process.stdout:
                    text = line.strip()
                    if not text:
                        continue
                    try:
                        data = json.loads(text)
                    except ValueError:
                        self.event(job, text, 'OUTPUT')
                        continue
                    if not isinstance(data, dict):
                        continue
                    if data.get('type') == 'thread.started':
                        job['conversationId'] = data.get('thread_id')
                    if data.get('type') == 'turn.completed':
                        completed = True
                    if data.get('type') in ('error', 'turn.failed'):
                        failed_event = True
                        self.event(job, data.get('message') or data.get('error') or data['type'], 'ERROR')
                    elif isinstance(data.get('item'), dict) and data['item'].get('type') == 'agent_message':
                        self.event(job, data['item'].get('text', ''), 'OUTPUT')
                    elif not isinstance(data.get('item'), dict) or data['item'].get('type') != 'reasoning':
                        self.event(job, text, 'STATUS')
                    if data.get('context'):
                        job['artifactUri'] = data['context']
                        self.event(job, data['context'], 'ARTIFACT')
                process.stdout.close()
                code = process.wait()
            finally:
                timer.cancel()
            with self.lock:
                if job['status'] == 'CANCELLING':
                    job['status'] = 'CANCELLED'
                else:
                    success = code == 0 and not failed_event and (completed or job['mode'] == 'solve-loop')
                    job['status'] = 'COMPLETED' if success else 'FAILED'
                    job['error'] = None if success else f'Codex/loop exited {code} without verified completion'
                self.event(job, 'Job completed' if job['status'] == 'COMPLETED' else job.get('error') or 'Cancelled',
                           'STATUS' if job['status'] == 'COMPLETED' else 'ERROR')
        except Exception as error:
            if process is not None and process.poll() is None:
                try:
                    process.kill() if os.name == 'nt' else os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                except ProcessLookupError:
                    pass
            with self.lock:
                job['status'], job['error'] = 'FAILED', str(error)
                self.event(job, str(error), 'ERROR')

    def cancel(self, identifier, reason='Cancelled by user'):
        with self.lock:
            job = self.jobs[identifier]
            if job['status'] == 'RUNNING':
                job['status'], job['error'] = 'CANCELLING', reason
                process = self.processes.get(identifier)
                if process and process.poll() is None:
                    try:
                        process.terminate() if os.name == 'nt' else os.killpg(process.pid, signal.SIGTERM)
                        def finish_cancel():
                            try:
                                process.wait(timeout=5)
                            except subprocess.TimeoutExpired:
                                try:
                                    process.kill() if os.name == 'nt' else os.killpg(process.pid, signal.SIGKILL)
                                except ProcessLookupError:
                                    pass
                        threading.Thread(target=finish_cancel, daemon=True).start()
                    except ProcessLookupError:
                        pass
                else:
                    job['status'] = 'CANCELLED'
                self.event(job, reason, 'ERROR')
            return {'status': job['status']}

    def events(self, identifier, after):
        with self.lock:
            job = self.jobs[identifier]
            return {**{k: job.get(k) for k in ('status', 'error', 'conversationId', 'artifactUri', 'projectRoot')},
                    'events': [e for e in job['events'] if e['sequence'] > after]}


def handler(harness, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, code, data):
            raw = json.dumps(data).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(raw)

        def dispatch(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                return self.reply(401, {'error': 'Bearer authentication required'})
            try:
                parsed = urlsplit(self.path)
                path = parsed.path
                if self.command == 'GET':
                    if path == '/v1/health':
                        return self.reply(200, harness.health())
                    if path == '/v1/capabilities':
                        return self.reply(200, {'providerId': 'codex-cli', 'displayName': 'Codex CLI in Termux',
                                               'capabilities': [{'id': k, 'risk': v, 'description': k} for k, v in CAPABILITIES.items()]})
                    if path == '/v1/projects':
                        return self.reply(200, {'projects': [{'name': p.name, 'projectRoot': str(p)} for p in harness.projects.iterdir() if p.is_dir() and not p.is_symlink()]})
                    if path == '/v1/jobs':
                        return self.reply(200, {'jobs': [{k: j.get(k) for k in ('jobId', 'objective', 'projectRoot', 'status', 'error', 'createdAt', 'updatedAt')} for j in harness.jobs.values()]})
                    match = re.fullmatch(r'/v1/jobs/([A-Za-z0-9_-]+)/events', path)
                    if match:
                        return self.reply(200, harness.events(match[1], int(parse_qs(parsed.query).get('after', ['0'])[0])))
                if self.command == 'POST':
                    size = int(self.headers.get('Content-Length', 0))
                    if not 0 <= size <= 32768:
                        raise ValueError('Request too large')
                    body = json.loads(self.rfile.read(size) or b'{}')
                    if not isinstance(body, dict):
                        raise ValueError('Expected JSON object')
                    if path == '/v1/jobs':
                        return self.reply(202, harness.start(body))
                    if path == '/v1/demo':
                        return self.reply(202, harness.start(body, demo=True))
                    if path == '/v1/projects':
                        return self.reply(201, harness.create_project(body['name']))
                    if path == '/v1/settings':
                        return self.reply(200, harness.update_settings(body))
                    match = re.fullmatch(r'/v1/jobs/([A-Za-z0-9_-]+)/cancel', path)
                    if match:
                        return self.reply(200, harness.cancel(match[1]))
                return self.reply(404, {'error': 'Unknown endpoint'})
            except KeyError:
                self.reply(404, {'error': 'Unknown job or missing field'})
            except (ValueError, TypeError, OSError) as error:
                self.reply(400, {'error': str(error)})

        do_GET = dispatch
        do_POST = dispatch
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, default=Path.home() / 'lateral-agent')
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--loop-home', type=Path)
    parser.add_argument('--codex-command', default='["codex"]', help='Installer-controlled JSON argv')
    parser.add_argument('--serial')
    parser.add_argument('--port', type=int, default=8766)
    args = parser.parse_args()
    command = json.loads(args.codex_command)
    if not isinstance(command, list) or not command or any(not isinstance(x, str) or not x for x in command):
        parser.error('--codex-command must be a nonempty JSON argv array')
    harness = Harness(args.home, command, args.loop_home, args.serial)
    token_path = args.token_file or harness.state / 'bridge.token'
    token_path.parent.mkdir(parents=True, exist_ok=True)
    if token_path.is_symlink():
        parser.error('Token file may not be a symlink')
    if not token_path.exists():
        with token_path.open('x') as stream:
            stream.write(secrets.token_urlsafe(32))
    os.chmod(token_path, 0o600)
    token = token_path.read_text().strip()
    if len(token) < 24:
        parser.error('Token must contain at least 24 characters')
    server = ThreadingHTTPServer(('127.0.0.1', args.port), handler(harness, token))
    print(json.dumps({'url': f'http://127.0.0.1:{args.port}', 'tokenFile': str(token_path), 'projectsRoot': str(harness.projects)}), flush=True)
    def terminate(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for identifier in list(harness.jobs):
            harness.cancel(identifier, 'Service shutting down')
        for process in list(harness.processes.values()):
            try:
                process.wait(timeout=6)
            except subprocess.TimeoutExpired:
                process.kill() if os.name == 'nt' else os.killpg(process.pid, signal.SIGKILL)
        server.server_close()


if __name__ == '__main__':
    main()
