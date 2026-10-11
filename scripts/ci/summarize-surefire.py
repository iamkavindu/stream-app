"""Summarize Surefire XML and fail CI for empty, failed, or unexpectedly skipped tests."""

import argparse
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(directory: Path) -> tuple[str, bool]:
    files = sorted(directory.glob('TEST-*.xml'))
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    skipped = []
    problems = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
            if root.tag != 'testsuite':
                raise ValueError('Expected a Surefire testsuite root')
            counts = {key: int(root.get(key, '0')) for key in totals}
            if any(value < 0 for value in counts.values()):
                raise ValueError('Negative test count')
            for key, value in counts.items():
                totals[key] += value
            # List names without embedding test failure messages or system properties.
            for case in root.findall('testcase'):
                if case.find('skipped') is not None:
                    skipped.append(f"{case.get('classname', '')}.{case.get('name', '')}")
        except (ET.ParseError, OSError, ValueError) as error:
            problems.append(f'{path.name}: {error}')
    lines = [
        f'## JVM test reports: {directory.parent.parent.name}',
        '',
        '| Tests | Failures | Errors | Skipped |',
        '| --- | --- | --- | --- |',
        '| ' + ' | '.join(str(value) for value in totals.values()) + ' |',
        '',
        'This tier runs the default JVM suite with Docker-backed PostgreSQL/Floci.',
        'The POM excludes `slow` and `pipeline` tags; those tests are not counted here.',
        'Native Lambda, PowerShell, and browser playback/seek acceptance are outside this tier.',
    ]
    if not files or totals['tests'] == 0:
        problems.append('No executed tests were reported; this is not a passing test run.')
    if skipped:
        lines += ['', 'Unexpected skipped tests:'] + [f'- `{name}`' for name in skipped]
    if problems:
        lines += ['', 'Report problems:'] + [f'- {problem}' for problem in problems]
    passed = not problems and not skipped and not any(totals[key] for key in ('failures', 'errors', 'skipped'))
    lines += ['', 'Report gate: ' + ('PASS' if passed else 'FAIL')]
    return '\n'.join(lines) + '\n', passed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report, passed = summarize(args.directory)
    print(report, end='')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as output:
            output.write(report)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
