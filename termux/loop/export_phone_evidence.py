#!/usr/bin/env python3
"""Run inside ordinary Termux to export one selected solving run to shared storage."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import tarfile
import uuid


def export_run(run, destination):
    run = Path(run).resolve(strict=True)
    if not run.is_dir() or run.parent.name != 'termux-loop-runs':
        raise ValueError('Select one run directory directly under termux-loop-runs')
    if not (run / 'result.json').is_file():
        raise ValueError('The selected run has no result.json')
    destination = Path(destination).resolve(strict=True)
    if not destination.is_dir() or destination == run or run in destination.parents:
        raise ValueError('Choose an existing export directory outside the selected run')
    # No source backups, APKs, Gradle caches, environment files, or ADB keys.
    candidates = [run / 'result.json']
    for attempt in sorted(run.glob('attempt-*')):
        if attempt.is_dir() and not attempt.is_symlink():
            candidates.extend(path for path in sorted(attempt.iterdir())
                              if path.suffix in ('.log', '.png', '.xml') and path.is_file())
    entries = []
    total = 0
    for path in candidates:
        if path.is_symlink() or run not in path.resolve(strict=True).parents:
            raise ValueError('Refusing a symlink or escaped evidence path')
        size = path.stat().st_size
        total += size
        if size > 64 * 1024 * 1024 or total > 256 * 1024 * 1024:
            raise ValueError('Evidence exceeds export limit: 64 MiB/file, 256 MiB total')
        entries.append((str(path.relative_to(run)).replace('\\', '/'), path.read_bytes()))
    manifest = {
        'runDirectory': str(run),
        'exportedAtUtc': datetime.now(timezone.utc).isoformat(),
        'files': {name: hashlib.sha256(data).hexdigest() for name, data in entries},
        'excluded': ['APKs', 'context.json', 'source files/backups', 'toolchains', 'credentials'],
    }
    entries.append(('EXPORT.json', (json.dumps(manifest, indent=2) + '\n').encode()))
    archive = destination / ('termux-loop-evidence-' + uuid.uuid4().hex[:12] + '.tar.gz')
    with archive.open('xb') as output:
        with tarfile.open(fileobj=output, mode='w:gz') as package:
            for name, data in entries:
                item = tarfile.TarInfo(run.name + '/' + name)
                item.size = len(data)
                item.mode = 0o600
                package.addfile(item, io.BytesIO(data))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    with archive.with_name(archive.name + '.sha256').open('x', encoding='ascii') as output:
        output.write(digest + '  ' + archive.name + '\n')
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path, default=Path.home() / 'storage/downloads')
    args = parser.parse_args()
    try:
        print(export_run(args.run_dir, args.output_dir))
        return 0
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
