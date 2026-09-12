import http.client
import json
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from agent_service import Harness, CAPABILITIES, codex_argv, codex_environment, write_local_catalog, handler, save


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name).resolve()
        self.auth = True
        self.devices = 'List of devices attached\n127.0.0.1:5555\tdevice\nother\tdevice\n'
        self.harness = Harness(self.home, ['codex'], probe_run=self.probe)

    def probe(self, command, **kwargs):
        if command[-1] == '--version':
            return subprocess.CompletedProcess(command, 0, 'codex-cli 1.0')
        if command[-2:] == ['login', 'status']:
            return subprocess.CompletedProcess(command, 0 if self.auth else 1, 'private auth detail')
        return subprocess.CompletedProcess(command, 0, self.devices)

    def request(self):
        return {'jobId': 'test', 'objective': 'Describe this project',
                'projectRoot': str(self.home / 'projects/workspace'), 'grantedCapabilities': list(CAPABILITIES)}

    def wait_job(self, name='test'):
        deadline = time.monotonic() + 5
        while self.harness.jobs[name]['status'] in ('RUNNING', 'CANCELLING') and time.monotonic() < deadline:
            time.sleep(.02)
        return self.harness.jobs[name]

    def test_missing_login_is_explicit_and_starts_no_job(self):
        self.auth = False
        health = self.harness.health()
        self.assertEqual(health['codex']['authStatus'], 'needs-login')
        self.assertNotIn('private auth detail', json.dumps(health))
        with self.assertRaisesRegex(ValueError, 'needs login'):
            self.harness.start(self.request())
        self.assertEqual(self.harness.jobs, {})

    def test_multiple_devices_never_auto_selected(self):
        self.assertIsNone(self.harness.health()['adb']['serial'])
        self.harness.update_settings({'adbSerial': '127.0.0.1:5555'})
        self.assertEqual(self.harness.health()['adb']['serial'], '127.0.0.1:5555')
        with self.assertRaises(ValueError):
            self.harness.update_settings({'adbSerial': 'unknown'})

    def test_projects_stay_inside_managed_root(self):
        with self.assertRaises(ValueError):
            self.harness.project(str(self.home))
        with self.assertRaises(ValueError):
            self.harness.create_project('../outside')
        project = self.harness.create_project('new-project')
        self.assertEqual(self.harness.project(project['projectRoot']).name, 'new-project')

    def test_job_cannot_expand_grants_or_command(self):
        request = self.request()
        request['command'] = ['arbitrary']
        with self.assertRaises(ValueError):
            self.harness.start(request)
        request = self.request()
        request['grantedCapabilities'].append('filesystem.anywhere')
        with self.assertRaises(ValueError):
            self.harness.start(request)
        request['grantedCapabilities'] = []
        with self.assertRaises(ValueError):
            self.harness.start(request)

    def test_cli_completion_requires_structured_completion_and_exit_zero(self):
        for index, script in enumerate(('print("{}")',
                 'print(\'{"type":"turn.completed"}\')',
                 'import sys;print(\'{"type":"turn.completed"}\');sys.exit(1)')):
            request = self.request()
            request['jobId'] = str(index)
            self.harness.codex_command = [sys.executable, '-c', script]
            self.harness.start(request)
            job = self.wait_job(str(index))
            self.assertEqual(job['status'], 'COMPLETED' if index == 1 else 'FAILED')

    def test_cancel_running_job_and_persist_status(self):
        self.harness.codex_command = [sys.executable, '-c', 'import time;time.sleep(60)']
        self.harness.start(self.request())
        deadline = time.monotonic() + 3
        while 'test' not in self.harness.processes and time.monotonic() < deadline:
            time.sleep(.01)
        self.harness.cancel('test')
        self.assertEqual(self.wait_job()['status'], 'CANCELLED')
        saved = json.loads((self.home / 'state/jobs.json').read_text())
        self.assertEqual(saved['test']['status'], 'CANCELLED')

    def test_recovery_does_not_claim_resume_or_success(self):
        save(self.home / 'state/jobs.json', {'old': {'jobId': 'old', 'status': 'RUNNING', 'events': []}})
        recovered = Harness(self.home, ['codex'], probe_run=self.probe)
        self.assertEqual(recovered.jobs['old']['status'], 'FAILED')
        self.assertIn('not resumed', recovered.jobs['old']['error'])

    def test_oss_still_uses_actual_codex_cli(self):
        settings = {'codexMode': 'oss-lmstudio', 'model': 'explicit-model', 'lmstudioBaseUrl': 'http://10.0.2.2:1234/v1'}
        command = codex_argv(['codex'], settings, '/project', 'objective')
        self.assertEqual(command[0], 'codex')
        self.assertIn('--oss', command)
        self.assertIn('workspace-write', command)
        self.assertIn('web_search="disabled"', command)
        self.assertNotIn('--dangerously-bypass-approvals-and-sandbox', command)
        self.assertFalse(any('model_providers.lmstudio' in arg for arg in command))
        original = {'PATH': 'unchanged', 'CODEX_OSS_BASE_URL': 'stale'}
        environment = codex_environment(settings, original)
        self.assertEqual(environment['CODEX_OSS_BASE_URL'], 'http://10.0.2.2:1234/v1')
        self.assertEqual(original['CODEX_OSS_BASE_URL'], 'stale')
        self.assertNotIn('CODEX_OSS_BASE_URL', codex_environment({'codexMode': 'chatgpt'}, original))

    def test_fresh_demo_uses_codex_solver_and_preserves_previous_source(self):
        loop_home = self.home / 'loop-runtime'
        sample = loop_home / 'sample'
        original = Path(__file__).resolve().parents[1] / 'loop/sample'
        shutil.copytree(original, sample)
        (sample / 'local.properties').write_text('sdk.dir=/fake/sdk\n')
        self.harness.loop_home = loop_home
        first = self.harness.make_demo()
        second = self.harness.make_demo()
        self.assertNotEqual(first, second)
        config = json.loads((first / 'loop.json').read_text())
        self.assertTrue(config['solverCommand'][1].endswith('codex_solver.py'))
        self.assertNotIn('model_solver.py', json.dumps(config))
        source = 'app/src/main/java/com/example/termuxloop/MainActivity.java'
        self.assertIn('label.setTextSize(28);', (sample / source).read_text())
        self.assertNotIn('label.setTextSize(28);', (first / source).read_text())

    def test_explicit_startup_serial_is_persisted(self):
        Harness(self.home, ['codex'], serial='explicit', probe_run=self.probe)
        recovered = Harness(self.home, ['codex'], probe_run=self.probe)
        self.assertEqual(recovered.settings['adbSerial'], 'explicit')

    def test_local_catalog_omits_freeform_tool_keeps_exec_and_sandbox(self):
        self.harness.update_settings({'codexMode': 'oss-lmstudio', 'model': 'local-model'})
        settings = self.harness.execution_settings()
        model = json.loads(Path(settings['modelCatalogPath']).read_text())['models'][0]
        self.assertIsNone(model['apply_patch_tool_type'])
        self.assertEqual(model['shell_type'], 'unified_exec')
        self.assertEqual(model['slug'], 'local-model')
        command = codex_argv(['codex'], settings, self.home / 'projects/workspace', 'repair')
        self.assertIn('workspace-write', command)
        self.assertTrue(any(value.startswith('model_catalog_json=') for value in command))

    def test_http_requires_header_token_not_query(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler(self.harness, 'token-for-test'))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
            connection.request('GET', '/v1/capabilities?token=token-for-test')
            response = connection.getresponse()
            self.assertEqual(response.status, 401)
            response.read()
            connection.request('GET', '/v1/capabilities', headers={'Authorization': 'Bearer token-for-test'})
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            self.assertEqual(json.loads(response.read())['providerId'], 'codex-cli')
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
