#!/usr/bin/env python3
"""Conservative change-based selection; shared/unknown code changes run all suites."""
import argparse
import os
from pathlib import Path
import subprocess

ALL = ['security', 'api', 'release', 'outbox', 'database']
PREFIX = 'Companion/AdminSuite/'
ISOLATED = {
    'DatabaseInspector.java': ['database'],
    'OutboxStore.java': ['outbox'],
}
HARNESS = {
    'AppApiSecurityHarness.java': 'security',
    'AppApiIntegrationHarness.java': 'api',
    'Release820Harness.java': 'release',
    'Outbox840Harness.java': 'outbox',
    'Database850Harness.java': 'database',
}


def select(paths):
    selected = {'security', 'api'}  # Existing app API/authentication compatibility baseline.
    for path in paths:
        if path.startswith(PREFIX + 'src/main/java/') and Path(path).name in ISOLATED:
            selected.update(ISOLATED[Path(path).name])
        elif path.startswith(PREFIX + 'tools/tests/') and Path(path).name in HARNESS:
            selected.add(HARNESS[Path(path).name])
        elif path.startswith(PREFIX) or path == '.github/workflows/admin-suite.yml':
            return ALL.copy()  # Configuration, dependencies and shared changes can affect everything.
    return [key for key in ALL if key in selected]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--full', action='store_true')
    args = parser.parse_args()
    base = os.environ.get('BASE_SHA', '')
    if args.full or not base or set(base) == {'0'}:
        suites = ALL.copy()
    else:
        # The PR base comparison includes the full branch, not just its latest commit.
        result = subprocess.run(['git', 'diff', '--name-only', '-z', base, 'HEAD'],
                                check=True, capture_output=True)
        paths = result.stdout.decode().strip('\0').split('\0')
        suites = select(paths)
    print('Selected suites:', ', '.join(suites))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('args=' + ' '.join('--suite ' + key for key in suites) + '\n')
        output.write('database=' + str('database' in suites).lower() + '\n')


if __name__ == '__main__':
    main()
