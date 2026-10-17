"""Authoritative canonical test runner for the shared-campaign PC control plane.

Compiles the canonical test sources against build/classes, runs each entry-point
class in its own JVM (one at a time), and prints a PASS/FAIL summary. Follows the
repo resource discipline: nice -n 10, 96 MiB heap, ActiveProcessorCount=1, and the
scripts/resources.py RAM/load guard before every child process.

Exit code is 0 only when every canonical test passes AND the observed pass count
matches the expected count recorded here. The expected count is a guard against a
test silently disappearing from a file while still exiting 0.

Usage: python3 scripts/run_canonical_tests.py [--json build/canonical-result.json]
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'scripts'))
from resources import check, stop  # noqa: E402

ENGINE = Path('/opt/mindustry/server-release.jar')
BUILD = ROOT / 'build'
CLASSES = BUILD / 'classes'
TEST_CLASSES = BUILD / 'test-classes'
JAVAC = ['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1', '--release', '17']
# -ea is REQUIRED, not cosmetic: every canonical test asserts with the `assert`
# keyword, which is silently skipped without it. Running without -ea once produced
# a bogus "all green" result (see reports/2026-10-16-assertion-guard.md).
JAVA = ['nice', '-n', '10', 'java', '-ea', '-Xmx96m', '-XX:ActiveProcessorCount=1']
TEST_TIMEOUT = 90

# Canonical suite: 15 assertions across 4 entry points (BASELINE_BUILD_REPORT.md).
# `expected` is the number of [PASS] lines the entry point must print. `None` means
# the class reports success by exit code only and counts as a single test.
# The assert-using entry points call tests.TestGuard.assertEnabled() first, so a
# missing -ea surfaces as an [ERROR] line rather than a vacuous pass.
CANONICAL = [
    {
        'name': 'AcoIntegrationTest.testPingPong',
        'main': 'sc.aco.AcoIntegrationTest',
        'sources': ['tests/AcoIntegrationTest.java'],
        'expected': 1,
        'requires_assertions': True,
    },
    {
        'name': 'GameplayBoundaryTest',
        'main': 'tests.GameplayBoundaryTest',
        'sources': ['tests/GameplayBoundaryTest.java'],
        'expected': 11,
        'requires_assertions': True,
    },
    {
        'name': 'MigrationProtocolTest',
        'main': 'sc.aco.MigrationProtocolTest',
        'sources': ['tests/MigrationProtocolTest.java'],
        'expected': 2,
        'requires_assertions': True,
    },
    {
        'name': 'SimpleInitTest',
        'main': 'tests.SimpleInitTest',
        'sources': ['tests/SimpleInitTest.java'],
        'expected': None,
        'requires_assertions': False,
    },
    {
        # M1 wiring: UI action seam routing plus a real localhost PING/PONG
        # handshake through the wired AcoController path.
        'name': 'AcoWiringTest',
        'main': 'tests.AcoWiringTest',
        'sources': ['tests/AcoWiringTest.java'],
        'expected': 5,
        'requires_assertions': True,
    },
]


def run_capture(cmd, timeout):
    """Run one child with the shared resource guard; return (rc, combined output)."""
    check()
    print('$', ' '.join(map(str, cmd)), flush=True)
    p = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, out
    except subprocess.TimeoutExpired:
        stop(p)
        return None, '<timeout>'


def ensure_classes():
    if not CLASSES.is_dir():
        print('build/classes missing; running scripts/build.py first', flush=True)
        rc, out = run_capture([sys.executable, 'scripts/build.py'], timeout=120)
        print(out)
        if rc != 0:
            raise SystemExit('production build failed; cannot run canonical tests')


def compile_tests():
    TEST_CLASSES.mkdir(parents=True, exist_ok=True)
    # TestGuard.java is compiled unconditionally: it is the guard that makes a
    # dropped -ea fail loudly instead of producing a vacuous pass.
    sources = sorted({s for t in CANONICAL for s in t['sources']} | {'tests/TestGuard.java'})
    cp = f'{CLASSES}:{ENGINE}'
    rc, out = run_capture(JAVAC + ['-cp', cp, '-d', str(TEST_CLASSES)] + sources, timeout=120)
    if rc != 0:
        print(out)
        raise SystemExit('canonical test compilation failed')
    return sources


def run_test(test):
    rc, out = run_capture(
        JAVA + ['-cp', f'{TEST_CLASSES}:{CLASSES}:{ENGINE}', test['main']], timeout=TEST_TIMEOUT)
    print(out, end='' if out.endswith('\n') else '\n', flush=True)
    passes = len(re.findall(r'^\[PASS\] ', out, re.M))
    failures = re.findall(r'^\[(?:FAIL|ERROR)\] (.+)$', out, re.M)
    if rc is None:
        return 0, 0, ['timeout']
    # Belt-and-braces: even if a future test forgets TestGuard, an assertion-off
    # run is rejected when the guard's message appears anywhere in the output.
    if test['requires_assertions'] and 'assertions are DISABLED' in out:
        failures = failures + ['assertions were disabled; test result is meaningless']
    ok = rc == 0 and not failures and (test['expected'] is None or passes == test['expected'])
    if test['expected'] is not None and passes != test['expected']:
        failures = failures + [f'expected {test["expected"]} PASS lines, observed {passes}']
    # Exit-code-only entry points (e.g. SimpleInitTest) count as one canonical test.
    return ok, passes if test['expected'] is not None else (1 if ok else 0), failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--json', default='build/canonical-result.json')
    args = parser.parse_args()

    start = time.monotonic()
    ensure_classes()
    compile_tests()

    total_passed = 0
    results = []
    for test in CANONICAL:
        print(f'\n=== RUN {test["name"]} (expected {test["expected"] or "exit-code"}) ===', flush=True)
        ok, passes, failures = run_test(test)
        total_passed += passes
        for f in failures:
            print(f'[FAIL] {test["name"]}: {f}')
        results.append({
            'name': test['name'],
            'status': 'PASS' if ok else 'FAIL',
            'passed': passes,
            'expected': test['expected'],
            'failures': failures,
        })

    failed = [r for r in results if r['status'] == 'FAIL']
    print('\n=== CANONICAL SUMMARY ===')
    for r in results:
        print(f'  {r["status"]:4} {r["name"]} (passed={r["passed"]})')
    print(f'Total passed: {total_passed}')
    print(f'Tests failed: {len(failed)}')
    verdict = 'PASS' if not failed else 'FAIL'
    print(f'CANONICAL_TEST_SUITE: {verdict}')

    record = {
        'verdict': verdict,
        'total_passed': total_passed,
        'elapsed_s': round(time.monotonic() - start, 3),
        'results': results,
    }
    out_path = ROOT / args.json
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(record, indent=2) + '\n')
    raise SystemExit(0 if verdict == 'PASS' else 1)


if __name__ == '__main__':
    main()
