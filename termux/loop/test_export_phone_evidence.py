import hashlib
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from export_phone_evidence import export_run


class ExportPhoneEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / 'project/termux-loop-runs/run-device-test'
        self.attempt = self.run / 'attempt-01'
        self.attempt.mkdir(parents=True)
        self.output = self.root / 'Downloads'
        self.output.mkdir()
        (self.run / 'result.json').write_text(json.dumps({
            'status': 'succeeded', 'attempts': [{'number': 1, 'status': 'succeeded'}]}))

    def symlink(self, link, target, directory=False):
        try:
            link.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError) as error:
            self.skipTest('Symlinks unavailable on this host: ' + str(error))

    def test_exports_actual_runner_artifacts_with_valid_hashes_and_excludes_private_files(self):
        # solve_loop.py stores captured XML in ui.log, not ui.xml.
        evidence = {
            'build.log': b'BUILD SUCCESSFUL',
            'ui.log': b'<hierarchy><node text="TERMUX LOOP SOLVED" /></hierarchy>',
            'screenshot.png': b'\x89PNG\r\n\x1a\nfixture',
            'installed-apk-hash.log': b'123abc  /data/app/example/base.apk',
            'solver.log': b'repair applied',
        }
        for name, data in evidence.items():
            (self.attempt / name).write_bytes(data)
        (self.attempt / 'app.apk').write_bytes(b'APK excluded')
        (self.attempt / 'context.json').write_text('{"workspace":"private"}')
        backup = self.attempt / 'model-backup-test'
        backup.mkdir()
        (backup / 'MainActivity.java').write_text('private source')
        (backup / 'private.log').write_text('nested logs excluded')
        (self.run / 'env.sh').write_text('MODEL_API_KEY=secret')
        (self.run / 'adbkey').write_text('private key')
        sibling = self.run.parent / 'run-unrelated'
        sibling.mkdir()
        (sibling / 'result.json').write_text('private unrelated run')

        archive = export_run(self.run, self.output)
        digest_file = archive.with_name(archive.name + '.sha256')
        self.assertEqual(digest_file.read_text().split()[0],
                         hashlib.sha256(archive.read_bytes()).hexdigest())
        with tarfile.open(archive, 'r:gz') as package:
            prefix = self.run.name + '/'
            actual = set(package.getnames())
            expected = {prefix + 'result.json', prefix + 'EXPORT.json'}
            expected.update(prefix + 'attempt-01/' + name for name in evidence)
            self.assertEqual(actual, expected)
            manifest = json.load(package.extractfile(prefix + 'EXPORT.json'))
            for name, expected_digest in manifest['files'].items():
                data = package.extractfile(prefix + name).read()
                self.assertEqual(hashlib.sha256(data).hexdigest(), expected_digest)
            for name, data in evidence.items():
                self.assertEqual(package.extractfile(prefix + 'attempt-01/' + name).read(), data)
        second = export_run(self.run, self.output)
        self.assertNotEqual(archive, second)
        self.assertTrue(archive.exists())

    def test_rejects_file_symlink_before_creating_archive(self):
        secret = self.root / 'private-key'
        secret.write_text('must not be exported')
        self.symlink(self.attempt / 'build.log', secret)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            export_run(self.run, self.output)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_skips_attempt_directory_symlink(self):
        private = self.root / 'private'
        private.mkdir()
        (private / 'secret.log').write_text('must not be exported')
        self.symlink(self.run / 'attempt-02', private, directory=True)
        archive = export_run(self.run, self.output)
        with tarfile.open(archive, 'r:gz') as package:
            self.assertEqual(set(package.getnames()), {
                self.run.name + '/result.json', self.run.name + '/EXPORT.json'})


if __name__ == '__main__':
    unittest.main()
