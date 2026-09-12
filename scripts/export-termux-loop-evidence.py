"""Export only a loop workspace's runs/source, excluding toolchains and credentials."""
import argparse
import base64
from pathlib import Path
import re
import subprocess
import tarfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--output', type=Path, default=Path('termux/loop/evidence'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial):
        parser.error('Invalid serial')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        parser.error('Invalid label')
    if not re.fullmatch(r'/data/data/com[.]termux/files/home/[A-Za-z0-9_./-]+', args.workspace) or '..' in args.workspace.split('/'):
        parser.error('Expected an explicit workspace in Termux private home')
    target = args.output.resolve() / args.label
    target.mkdir(parents=True, exist_ok=True)
    remote = args.workspace + '/export-' + uuid.uuid4().hex + '.tar'
    adb = [args.adb, '-s', args.serial, 'shell', 'run-as', 'com.termux']
    subprocess.run(adb + ['/data/data/com.termux/files/usr/bin/tar', '-cf', remote,
                         '-C', args.workspace, 'termux-loop-runs', 'app/src',
                         'loop.json', 'gradle.properties'], check=True, timeout=120)
    encoded = subprocess.check_output(adb + ['/system/bin/base64', remote], timeout=120)
    archive = target / 'evidence.tar'
    archive.write_bytes(base64.b64decode(encoded))
    with tarfile.open(archive, 'r:') as package:
        package.extractall(target, filter='data')
    subprocess.run(adb + ['/system/bin/rm', remote], check=True, timeout=30)
    print(target)


if __name__ == '__main__':
    main()
