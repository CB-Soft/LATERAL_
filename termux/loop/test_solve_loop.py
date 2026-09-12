"""Protocol tests simulate failures; real Android evidence is a separate acceptance test."""
import contextlib
import io
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from solve_loop import run_loop, execute
from demo_solver import repair


class LoopTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.workspace = Path(self.temp.name)
        self.config = {'buildCommand': ['gradle', 'assembleDebug'], 'apk': 'app.apk',
                       'package': 'com.example.termuxloop', 'activity': '.MainActivity',
                       'expectedText': 'TERMUX LOOP SOLVED', 'settleSeconds': 0,
                       'solverCommand': ['repair-agent']}
        self.builds = 0
        self.calls = []
        self.mode = 'retry'
        self.solver_failed = False

    def fake(self, command, cwd, output, timeout, binary=False):
        self.calls.append(command)
        code, body = 0, ''
        if command[0] == 'gradle':
            self.builds += 1
            if self.mode == 'build-fail':
                code, body = 1, 'compiler error'
            elif self.mode != 'missing-apk':
                (cwd / 'app.apk').write_bytes(b'APK')
        elif command[0] == 'repair-agent':
            context = json.loads(Path(command[-1]).read_text())
            self.assertEqual(context['remainingAttempts'], 1)
            code = 1 if self.solver_failed else 0
        else:
            self.assertEqual(command[1:3], ['-s', '127.0.0.1:5555'])
            if 'date' in command:
                body = '09-08 12:34:56.123456789'
            if 'pm' in command:
                body = 'package:/data/app/example/base.apk'
            if 'sha256sum' in command:
                body = hashlib.sha256(b'APK').hexdigest() + '  /data/app/example/base.apk'
                if self.mode == 'wrong-apk':
                    body = '0' * 64 + '  /data/app/example/base.apk'
            if 'pidof' in command:
                body = '1234'
                if self.builds == 1 and self.mode == 'retry':
                    code, body = 1, ''
            if 'cat' in command:
                package = 'com.other.app' if self.mode == 'wrong-package' else self.config['package']
                body = '<hierarchy><node package="' + package + '" text="TERMUX LOOP SOLVED" /></hierarchy>'
                if self.mode == 'bad-xml':
                    body = 'not XML'
            if 'crash' in command and self.mode == 'crash-with-live-process':
                body = 'FATAL EXCEPTION Process: com.example.termuxloop'
            if 'install' in command and self.mode == 'install-fail':
                code, body = 1, 'INSTALL_FAILED'
            if 'screencap' in command:
                body = b'\x89PNG\r\n\x1a\n' if self.mode != 'bad-screenshot' else b''
        output.write_bytes(body if isinstance(body, bytes) else body.encode())
        return {'command': command, 'exitCode': code, 'log': str(output)}

    def run_case(self, attempts=2):
        with contextlib.redirect_stdout(io.StringIO()):
            return run_loop(self.workspace, self.config, '127.0.0.1:5555', attempts,
                            executor=self.fake, sleeper=lambda _: None)

    def test_retry_rebuild_install_and_verify(self):
        result = self.run_case()
        self.assertEqual(result['status'], 'succeeded')
        self.assertEqual(self.builds, 2)
        self.assertEqual(result['attempts'][0]['failure'], 'process-not-running')
        self.assertEqual(len([c for c in self.calls if 'install' in c]), 2)
        self.assertTrue((Path(result['runDirectory']) / 'result.json').exists())
        self.assertFalse(any('-c' in c and 'logcat' in c for c in self.calls))
        self.assertTrue(all('-T' in c for c in self.calls if 'logcat' in c))

    def test_failed_build_never_installs(self):
        self.mode = 'build-fail'
        result = self.run_case()
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(self.builds, 2)
        self.assertFalse(any('install' in c for c in self.calls))

    def test_no_false_success(self):
        for mode in ('missing-apk', 'wrong-package', 'bad-xml', 'crash-with-live-process', 'install-fail', 'bad-screenshot', 'wrong-apk'):
            with self.subTest(mode=mode):
                self.mode = mode
                (self.workspace / 'app.apk').unlink(missing_ok=True)
                result = self.run_case(1)
                self.assertEqual(result['status'], 'failed')

    def test_solver_failure_stops(self):
        self.solver_failed = True
        result = self.run_case()
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['stopReason'], 'solver-failed')
        self.assertEqual(self.builds, 1)

    def test_without_solver_stops_after_first_failure(self):
        self.config.pop('solverCommand')
        self.assertEqual(len(self.run_case()['attempts']), 1)

    def test_paths_and_bounds_rejected(self):
        for invalid in ('../outside.apk', str(self.workspace.parent / 'outside.apk')):
            self.config['apk'] = invalid
            with self.assertRaises(ValueError):
                self.run_case()
        self.config['apk'] = 'app.apk'
        with self.assertRaises(ValueError):
            self.run_case(0)

    def test_string_command_rejected(self):
        self.config['buildCommand'] = 'gradle assembleDebug; arbitrary'
        with self.assertRaises(ValueError):
            self.run_case()

    def test_command_timeout_is_failure(self):
        result = execute([sys.executable, '-c', 'import time; time.sleep(60)'], self.workspace,
                         self.workspace / 'timeout.log', 0.1)
        self.assertEqual(result['exitCode'], 124)

    def test_missing_executable_is_failure(self):
        result = execute([str(self.workspace / 'nonexistent-command')], self.workspace,
                         self.workspace / 'missing.log', 1)
        self.assertEqual(result['exitCode'], 127)

    def test_cancellation_persists_failed_evidence(self):
        def cancelled(*args, **kwargs):
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt), contextlib.redirect_stdout(io.StringIO()):
            run_loop(self.workspace, self.config, '127.0.0.1:5555', executor=cancelled)
        result_path = next((self.workspace / 'termux-loop-runs').glob('*/result.json'))
        result = json.loads(result_path.read_text())
        self.assertEqual(result['status'], 'cancelled')
        self.assertEqual(result['attempts'][0]['failure'], 'cancelled')

    @unittest.skipIf(os.name == 'nt', 'POSIX/Termux process groups')
    def test_sigterm_kills_build_descendants(self):
        pid_file = self.workspace / 'child.pid'
        script = ('import subprocess,time,pathlib,sys; '
                  'child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"]); '
                  'pathlib.Path(sys.argv[1]).write_text(str(child.pid)); time.sleep(60)')
        self.config['buildCommand'] = [sys.executable, '-c', script, str(pid_file)]
        config_file = self.workspace / 'config.json'
        config_file.write_text(json.dumps(self.config))
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('solve_loop.py')),
                                    '--workspace', str(self.workspace), '--config', str(config_file),
                                    '--serial', '127.0.0.1:5555'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        child_pid = None
        try:
            deadline = time.monotonic() + 10
            while not pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(pid_file.exists(), 'Build child did not start')
            child_pid = int(pid_file.read_text())
            process.send_signal(signal.SIGTERM)
            self.assertEqual(process.wait(timeout=5), 130)
            # Android/Linux can briefly retain a dead orphan as a zombie pending reaping.
            status = Path('/proc') / str(child_pid) / 'status'
            deadline = time.monotonic() + 2
            while status.exists() and 'State:\tZ' not in status.read_text() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(not status.exists() or 'State:\tZ' in status.read_text())
            result_path = next((self.workspace / 'termux-loop-runs').glob('*/result.json'))
            self.assertEqual(json.loads(result_path.read_text())['status'], 'cancelled')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            if child_pid:
                try:
                    os.kill(child_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_demo_repair_is_scoped_and_single_use(self):
        source = self.workspace / 'app/src/main/java/com/example/termuxloop/MainActivity.java'
        source.parent.mkdir(parents=True)
        source.write_text('private static final boolean BROKEN = true;')
        context = self.workspace / 'context.json'
        context.write_text(json.dumps({'workspace': str(self.workspace), 'package': 'com.example.termuxloop',
                                       'attempt': {'failure': 'process-not-running'}}))
        repair(context)
        self.assertIn('BROKEN = false', source.read_text())
        with self.assertRaises(ValueError):
            repair(context)


if __name__ == '__main__':
    unittest.main()
