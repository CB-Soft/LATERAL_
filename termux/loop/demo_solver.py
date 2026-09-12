#!/usr/bin/env python3
"""Deterministic demo adapter, NOT an AI coding agent. Receives context JSON path."""
import json
import sys
from pathlib import Path


def repair(context_path):
    context = json.loads(Path(context_path).read_text(encoding='utf-8'))
    workspace = Path(context['workspace']).resolve(strict=True)
    source = (workspace / 'app/src/main/java/com/example/termuxloop/MainActivity.java').resolve(strict=True)
    if workspace not in source.parents or context['package'] != 'com.example.termuxloop':
        raise ValueError('Demo repair only accepts the bundled sample')
    text = source.read_text(encoding='utf-8')
    marker = 'private static final boolean BROKEN = true;'
    if text.count(marker) != 1:
        raise ValueError('Expected exactly one unrepaired demo marker')
    if context['attempt']['failure'] not in ('process-not-running', 'application-crashed', 'ui-verification-failed', 'launch-failed'):
        raise ValueError('Demo repair only handles a launched sample failure')
    source.write_text(text.replace(marker, 'private static final boolean BROKEN = false;'), encoding='utf-8')
    print('Deterministic demo repair: disabled the known intentional crash.')


if __name__ == '__main__':
    try:
        repair(sys.argv[1])
    except (OSError, ValueError, KeyError, IndexError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
