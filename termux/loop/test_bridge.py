import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from bridge import CAPABILITIES, Jobs, handler


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.jobs = Jobs(self.temporary.name, [sys.executable, '-c', 'print("verified")'])
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), handler(self.jobs, 'test-token'))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temporary.cleanup()

    def request(self, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base + path, data=data)
        with urlopen(request) as response:
            return json.load(response)

    def payload(self):
        return dict(jobId='example', projectRoot=str(Path(self.temporary.name).resolve()),
                    grantedCapabilities=list(CAPABILITIES))

    def test_token_required(self):
        with self.assertRaises(HTTPError) as error:
            self.request('/wrong/v1/health')
        self.assertEqual(error.exception.code, 403)
        self.assertTrue(self.request('/test-token/v1/health')['ok'])

    def test_rejects_other_workspace_or_missing_grants(self):
        for change in [{'projectRoot': '/'}, {'grantedCapabilities': []}]:
            payload = self.payload() | change
            with self.assertRaises(HTTPError) as error:
                self.request('/test-token/v1/jobs', payload)
            self.assertEqual(error.exception.code, 400)
        self.assertFalse(self.jobs.jobs)

    def test_events_completion_and_duplicate_rejection(self):
        self.request('/test-token/v1/jobs', self.payload())
        for _ in range(100):
            result = self.request('/test-token/v1/jobs/example/events?after=0')
            if result['status'] != 'RUNNING':
                break
            time.sleep(.02)
        self.assertEqual(result['status'], 'COMPLETED')
        self.assertTrue(any(e['message'] == 'verified' for e in result['events']))
        sequence = result['events'][-1]['sequence']
        self.assertEqual(self.request(f'/test-token/v1/jobs/example/events?after={sequence}')['events'], [])
        with self.assertRaises(HTTPError):
            self.request('/test-token/v1/jobs', self.payload())

    def test_failed_runner_never_completes(self):
        self.jobs.command = [sys.executable, '-c', 'raise SystemExit(1)']
        self.request('/test-token/v1/jobs', self.payload())
        for _ in range(100):
            result = self.jobs.events('example', 0)
            if result['status'] != 'RUNNING':
                break
            time.sleep(.02)
        self.assertEqual(result['status'], 'FAILED')


if __name__ == '__main__':
    unittest.main()
