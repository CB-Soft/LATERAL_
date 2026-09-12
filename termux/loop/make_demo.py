#!/usr/bin/env python3
"""Create a fresh demo workspace without changing an earlier run's source."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--model', action='store_true')
    parser.add_argument('--compile-error', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--serial')
    args = parser.parse_args()
    if args.compile_error and not args.model:
        parser.error('--compile-error requires --model; the deterministic adapter fixes only the crash')
    if args.run and not args.serial:
        parser.error('--run requires --serial')
    if args.run and args.model and not (os.environ.get('MODEL_BASE_URL') and os.environ.get('MODEL_NAME')):
        parser.error('Set MODEL_BASE_URL and MODEL_NAME for a model run')
    home = args.home.resolve(strict=True)
    sample = home / 'sample'
    workspace = home / 'projects' / ('demo-' + uuid.uuid4().hex[:12])
    workspace.mkdir(parents=True)
    files = ['settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew',
             'loop.json', 'local.properties', 'app/build.gradle',
             'app/src/main/AndroidManifest.xml',
             'app/src/main/java/com/example/termuxloop/MainActivity.java']
    for relative in files:
        source = sample / relative
        if relative == 'local.properties' and not source.exists():
            continue
        target = workspace / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    source = workspace / files[-1]
    text, count = re.subn(r'private static final boolean BROKEN = (true|false);',
                         'private static final boolean BROKEN = true;', source.read_text())
    if count != 1:
        raise ValueError('The bundled sample marker is missing; no existing workspace was modified')
    if args.compile_error:
        if text.count('label.setTextSize(28);') != 1:
            raise ValueError('Compile-error fixture line is missing')
        text = text.replace('label.setTextSize(28);', 'label.setTextSize(28)')
    source.write_text(text)
    config_path = workspace / 'loop.json'
    config = json.loads(config_path.read_text())
    if args.model:
        config['solverCommand'] = [sys.executable, str(home / 'model_solver.py'),
                                  '--file', files[-1]]
    config_path.write_text(json.dumps(config, indent=2) + '\n')
    command = [sys.executable, '-u', str(home / 'solve_loop.py'), '--workspace',
               str(workspace), '--config', str(config_path), '--max-attempts', '3']
    if args.serial:
        command += ['--serial', args.serial]
    if not args.model:
        command += ['--demo-solver']
    print(json.dumps(dict(workspace=str(workspace), config=str(config_path), command=command)), flush=True)
    return subprocess.call(command) if args.run else 0


if __name__ == '__main__':
    sys.exit(main())
