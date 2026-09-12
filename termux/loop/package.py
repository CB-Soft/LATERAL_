#!/usr/bin/env python3
"""Build a source-only portable installer archive. Never package device secrets/cache."""
import hashlib
import io
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent
FILES = [
    'install.sh', 'package.py', 'README.md', 'PHONE_SETUP.md', '.gitignore', 'LICENSE',
    'solve_loop.py', 'demo_solver.py', 'model_solver.py', 'bridge.py', 'make_demo.py',
    'test_solve_loop.py', 'test_model_solver.py', 'test_bridge.py',
    'export_phone_evidence.py', 'test_export_phone_evidence.py',
    'sample/settings.gradle', 'sample/build.gradle', 'sample/gradle.properties',
    'sample/gradlew', 'sample/loop.json', 'sample/app/build.gradle',
    'sample/app/src/main/AndroidManifest.xml',
    'sample/app/src/main/java/com/example/termuxloop/MainActivity.java',
]


def main():
    output = ROOT / 'dist'
    output.mkdir(exist_ok=True)
    archive = output / 'termux-gradle-loop.tar.gz'
    entries = []
    for name in FILES:
        path = ROOT / name
        if not path.is_file():
            raise FileNotFoundError(path)
        # Normalize Windows checkout line endings for Termux shell scripts.
        data = path.read_bytes().replace(b'\r\n', b'\n')
        entries.append((name, data))
    manifest = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                       for name, data in entries).encode()
    entries.append(('SHA256SUMS', manifest))
    with tarfile.open(archive, 'w:gz') as package:
        for name, data in entries:
            info = tarfile.TarInfo('termux-gradle-loop/' + name)
            info.size = len(data)
            info.mode = 0o755 if name.endswith('.sh') or name.endswith('/gradlew') else 0o644
            info.mtime = 0
            package.addfile(info, io.BytesIO(data))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(archive.suffix + '.sha256').write_text(
        digest + '  ' + archive.name + '\n', encoding='ascii')
    print(archive)
    print(digest)


if __name__ == '__main__':
    main()
