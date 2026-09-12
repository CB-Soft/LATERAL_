import io
import contextlib
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch
from model_solver import apply_repair, load_sources, request_repair, diagnostics, scoped_crash, parse_model_content, MAX_FILE_BYTES, MAX_HTTP_ERROR_BYTES


class ModelSolverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.workspace = Path(self.temp.name).resolve()
        self.source = self.workspace / 'Main.java'
        self.source.write_text('original', encoding='utf-8')
        self.sources = load_sources(self.workspace, ['Main.java'])
        self.artifacts = self.workspace / 'attempt'
        self.artifacts.mkdir()

    def apply(self, response):
        return apply_repair(self.workspace, self.sources, response, self.artifacts)

    def test_valid_repair_keeps_original_backup(self):
        result = self.apply({'files': {'Main.java': 'fixed'}})
        self.assertEqual(self.source.read_text(), 'fixed')
        self.assertEqual((Path(result['backupDirectory']) / 'Main.java').read_text(), 'original')

    def test_invalid_responses_do_not_modify_source(self):
        for response in (None, [], {}, {'files': {}}, {'files': []}, {'files': {'Main.java': 2}},
                         {'files': {'Main.java': ''}}, {'files': {'Main.java': 'nul\0'}},
                         {'files': {'Main.java': 'x' * (MAX_FILE_BYTES + 1)}},
                         {'files': {'Main.java': 'fixed', '../outside.java': 'bad'}},
                         {'files': {'Main.java': 'fixed', '/tmp/outside': 'bad'}},
                         {'files': {'Main.java': 'original'}}):
            with self.subTest(response_type=type(response)):
                with self.assertRaises(ValueError):
                    self.apply(response)
                self.assertEqual(self.source.read_text(), 'original')
                self.assertEqual(list(self.artifacts.iterdir()), [])

    def test_intervening_edit_is_not_overwritten(self):
        self.source.write_text('user edit')
        with self.assertRaises(ValueError):
            self.apply({'files': {'Main.java': 'fixed'}})
        self.assertEqual(self.source.read_text(), 'user edit')

    def test_outside_and_noncanonical_allowlist_rejected(self):
        for name in ('../Main.java', '/Main.java', str(self.source), './Main.java', 'a/../Main.java', 'a\\Main.java'):
            with self.subTest(name=name):
                with self.assertRaises((ValueError, OSError)):
                    load_sources(self.workspace, [name])

    def test_symlink_is_rejected(self):
        link = self.workspace / 'link.java'
        try:
            link.symlink_to(self.source)
        except OSError:
            self.skipTest('Host does not permit symlinks')
        with self.assertRaises(ValueError):
            load_sources(self.workspace, ['link.java'])

    def test_response_parsing_without_network(self):
        response = {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({'files': {'Main.java': 'fixed'}})}}]}
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(response).encode())) as request:
            result = request_repair('http://localhost:1234/v1', 'explicit-model', None, self.sources, {})
        self.assertEqual(result, {'files': {'Main.java': 'fixed'}})
        self.assertEqual(request.call_args.args[0].full_url, 'http://localhost:1234/v1/chat/completions')
        body = json.loads(request.call_args.args[0].data)
        self.assertEqual(body['response_format']['type'], 'json_schema')
        schema = body['response_format']['json_schema']['schema']
        self.assertFalse(schema['additionalProperties'])
        self.assertFalse(schema['properties']['files']['additionalProperties'])
        self.assertEqual(body['temperature'], 0.2)

    def test_truncated_response_rejected(self):
        response = {'choices': [{'finish_reason': 'length', 'message': {'content': '{}'}}]}
        with patch('urllib.request.urlopen', side_effect=[io.BytesIO(json.dumps(response).encode()) for _ in range(2)]) as request:
            with self.assertRaises(ValueError):
                request_repair('http://localhost:1234/v1', 'model', None, self.sources, {})
        self.assertEqual(request.call_count, 2)
        self.assertEqual(json.loads(request.call_args_list[1].args[0].data)['max_tokens'],
                         min(32768, 2 * json.loads(request.call_args_list[0].args[0].data)['max_tokens']))

    def test_complete_wrappers_parse_without_accepting_analysis(self):
        content = '{"files":{"Main.java":"fixed"}}'
        for wrapped in (content, '```json\n' + content + '\n```',
                        '<think>finished reasoning</think>\n' + content,
                        '<|channel|>analysis\nreasoning\n<|channel|>final\n' + content,
                        '<channel>analysis\nreasoning\n<channel>final\n```json\n' + content + '\n```',
                        '<|channel|>final<|message|>' + content + '<|end|>'):
            with self.subTest(wrapper=wrapped[:30]):
                self.assertEqual(parse_model_content(wrapped), json.loads(content))
        for incomplete in ('<think>unfinished ' + content,
                           '<|channel|>analysis ' + content,
                           '<|channel|>analysis one <|channel|>analysis ' + content,
                           '```json\n' + content,
                           'Here is JSON: ' + content):
            with self.subTest(incomplete=incomplete[:30]):
                with self.assertRaises(ValueError):
                    parse_model_content(incomplete)

    def test_bad_format_saved_locally_then_retried_without_editing(self):
        bad = {'choices': [{'finish_reason': 'stop', 'message': {'content': '<think>incomplete'}}]}
        good = {'choices': [{'finish_reason': 'stop', 'message': {'content': '```json\n{"files":{"Main.java":"fixed"}}\n```'}}]}
        with patch('urllib.request.urlopen', side_effect=[io.BytesIO(json.dumps(item).encode()) for item in (bad, good)]) as request:
            result = request_repair('http://localhost:1234/v1', 'model', None, self.sources, {}, diagnostic_dir=self.artifacts)
        self.assertEqual(request.call_count, 2)
        self.assertEqual(result, {'files': {'Main.java': 'fixed'}})
        self.assertEqual(self.source.read_text(), 'original')
        saved = list(self.artifacts.glob('model-response-invalid-*.json'))
        self.assertEqual(len(saved), 1)
        self.assertEqual(json.loads(saved[0].read_text()), bad)

    def test_credential_url_rejected_before_network(self):
        with patch('urllib.request.urlopen') as request:
            with self.assertRaises(ValueError):
                request_repair('https://user:secret@example.test/v1', 'model', None, self.sources, {})
            request.assert_not_called()

    def test_diagnostics_exclude_other_apps_preserve_target_stack(self):
        evidence = {
            'ui': '<hierarchy><node package="com.target.app" text="target text" content-desc="target label" />'
                  '<node package="com.private.other" text="private message" content-desc="secret" /></hierarchy>',
            'crash': 'E/AndroidRuntime( 123): FATAL EXCEPTION: main\n'
                     'E/AndroidRuntime( 999): Process: com.private.other, PID: 999\n'
                     'E/AndroidRuntime( 123): Process: com.target.app, PID: 123\n'
                     'E/AndroidRuntime( 999): private stack\n'
                     'E/AndroidRuntime( 123): java.lang.IllegalStateException: target bug\n'
                     'E/AndroidRuntime( 123):     at com.target.app.Main.onCreate(Main.java:5)\n',
            'logcat': 'private notification text',
            'build': 'project compiler error'
        }
        steps = []
        for name, content in evidence.items():
            path = self.artifacts / (name + '.log')
            path.write_text(content)
            steps.append({'name': name, 'log': str(path)})
        context = {'package': 'com.target.app', 'expectedText': 'done',
                   'attempt': {'failure': 'application-crashed', 'steps': steps}}
        result = diagnostics(context, self.artifacts / 'context.json')
        serialized = json.dumps(result)
        self.assertNotIn('private', serialized)
        self.assertNotIn('secret', serialized)
        self.assertNotIn('logcat', result['logs'])
        self.assertIn('FATAL EXCEPTION', result['logs']['crash'])
        self.assertIn('Main.java:5', result['logs']['crash'])
        self.assertEqual(result['logs']['ui'], [{'text': 'target text', 'contentDescription': 'target label'}])
        self.assertEqual(result['logs']['build'], 'project compiler error')

    def test_ambiguous_crash_pid_is_excluded(self):
        crash = ('E/AndroidRuntime(12): Process: com.target.app, PID: 12\n'
                 'E/AndroidRuntime(12): Process: com.other.app, PID: 12\n'
                 'E/AndroidRuntime(12): secret\n')
        self.assertEqual(scoped_crash(crash, 'com.target.app'), '')

    def test_invalid_token_limit_rejected_before_network(self):
        with patch.dict('os.environ', {'MODEL_MAX_TOKENS': '32769'}), patch('urllib.request.urlopen') as request:
            with self.assertRaises(ValueError):
                request_repair('http://localhost:1234/v1', 'model', None, self.sources, {})
            request.assert_not_called()

    def test_http_error_saved_bounded_and_closed_without_edits(self):
        body = io.BytesIO(b'private diagnostic ' + b'x' * MAX_HTTP_ERROR_BYTES)
        error = urllib.error.HTTPError('http://localhost:1234/v1/chat/completions', 400,
                                       'Bad Request', {}, body)
        with patch('urllib.request.urlopen', side_effect=error) as request:
            with self.assertRaises(urllib.error.HTTPError), contextlib.redirect_stdout(io.StringIO()) as stdout:
                request_repair('http://localhost:1234/v1', 'model', None, self.sources, {}, diagnostic_dir=self.artifacts)
        request.assert_called_once()
        self.assertTrue(body.closed)
        self.assertEqual(stdout.getvalue(), '')
        self.assertEqual(self.source.read_text(), 'original')
        files = list(self.artifacts.iterdir())
        self.assertEqual(len(files), 1)
        self.assertEqual(files[0].suffix, '.json')
        saved = json.loads(files[0].read_text())
        self.assertEqual(saved['status'], 400)
        self.assertEqual(saved['requestNumber'], 1)
        self.assertTrue(saved['bodyTruncated'])
        self.assertEqual(len(saved['body'].encode()), MAX_HTTP_ERROR_BYTES)
        self.assertFalse(list(self.artifacts.glob('model-backup-*')))

    def test_http_error_closed_without_diagnostic_directory(self):
        body = io.BytesIO(b'error')
        error = urllib.error.HTTPError('http://localhost:1234/v1/chat/completions', 400, 'Bad Request', {}, body)
        with patch('urllib.request.urlopen', side_effect=error):
            with self.assertRaises(urllib.error.HTTPError):
                request_repair('http://localhost:1234/v1', 'model', None, self.sources, {})
        self.assertTrue(body.closed)
        self.assertEqual(list(self.artifacts.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
