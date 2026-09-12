#!/usr/bin/env python3
"""Build a small APK asset containing runtime source; binaries download after consent."""
import gzip
import hashlib
import io
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent
FILES = ['install.sh', 'lateral-agent', 'lateral-codex', 'manage_service.py',
         'agent_service.py', 'codex_solver.py', 'manifest.json', 'README.md']
LOOP_FILES = ['install.sh', 'solve_loop.py', 'demo_solver.py', 'model_solver.py',
              'bridge.py', 'make_demo.py', 'export_phone_evidence.py', 'PHONE_SETUP.md',
              'sample/settings.gradle', 'sample/build.gradle', 'sample/gradle.properties',
              'sample/gradlew', 'sample/loop.json', 'sample/app/build.gradle',
              'sample/app/src/main/AndroidManifest.xml',
              'sample/app/src/main/java/com/example/termuxloop/MainActivity.java']


def main():
    target = ROOT.parents[1] / 'app/src/dev/assets/agent-runtime.tar.gz'
    target.parent.mkdir(parents=True, exist_ok=True)
    entries = [(name, (ROOT / name).read_bytes().replace(b'\r\n', b'\n')) for name in FILES]
    entries.extend(('loop/' + name, (ROOT.parent / 'loop' / name).read_bytes().replace(b'\r\n', b'\n'))
                   for name in LOOP_FILES)
    sums = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in entries)
    entries.append(('SHA256SUMS', sums.encode('ascii')))
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w') as archive:
        for name, data in entries:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o700
            archive.addfile(info, io.BytesIO(data))
    data = gzip.compress(raw.getvalue(), mtime=0)
    if len(data) > 150_000:
        raise ValueError('Runtime asset exceeds the Android bootstrap Binder budget')
    target.write_bytes(data)
    target.with_name(target.name + '.sha256').write_text(hashlib.sha256(data).hexdigest() + '\n')
    print(target)
    print(str(len(data)) + ' bytes; SHA256 ' + hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    main()
