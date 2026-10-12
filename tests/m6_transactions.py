"""Real-class transaction harness: SectorHandover atomic transfer + GlobalLedger WAL/snapshot.
Compiles tests/TransactionTest.java against build/classes so the shipped bytecode is exercised.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'scripts'))
from resources import check

parser = argparse.ArgumentParser()
parser.add_argument('--label', default='m6')
parser.add_argument('--jar', default='build/shared-campaign-pc.jar')
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.label)
os.chdir(ROOT)

ENGINE = Path('/opt/mindustry/server-release.jar')
CLASSES = ROOT / 'build/classes'
JAR = (ROOT / args.jar).resolve()
TEST_CLASSES = ROOT / 'build/test-classes'
TEST_DIR = ROOT / 'build/transaction-test'
record = dict(passed=False, scope='SectorHandover atomic transfer + GlobalLedger WAL/snapshot durability')

try:
    check()
    assert CLASSES.is_dir(), 'Run scripts/build.py first'
    assert JAR.is_file(), 'Production JAR required'
    TEST_CLASSES.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join([str(TEST_CLASSES), str(CLASSES), str(ENGINE)])
    compile_run = subprocess.run(
        ['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1', '--release', '17',
         '-cp', os.pathsep.join([str(CLASSES), str(ENGINE)]), '-d', str(TEST_CLASSES),
         str(ROOT / 'tests/TransactionTest.java')],
        capture_output=True, text=True, timeout=60)
    record['compile'] = dict(exit=compile_run.returncode, stderr=compile_run.stderr[-1500:])
    assert compile_run.returncode == 0, 'Harness failed to compile'
    run = subprocess.run(
        ['nice', '-n', '10', 'java', '-Xms24m', '-Xmx160m', '-XX:ActiveProcessorCount=1',
         '-Dsc.test.dir=' + str(TEST_DIR),
         '-cp', classpath, 'fixture.TransactionTest'],
        capture_output=True, text=True, timeout=90)
    record['exit'] = run.returncode
    record['stdout'] = run.stdout[-4000:]
    record['stderr'] = run.stderr[-2000:]
    assert run.returncode == 0, 'Transaction harness failed'
    assert 'TRANSACTION_OK' in run.stdout, 'Harness did not reach its success marker'
    record['checks'] = run.stdout.count('PASS ')
    record['passed'] = True
except Exception as e:
    record['error'] = f'{type(e).__name__}: {e}'
finally:
    (ROOT / 'build' / f'{args.label}-result.json').write_text(json.dumps(record, indent=2) + '\n')

print(json.dumps({k: v for k, v in record.items() if k not in ('stdout', 'stderr')}, indent=2))
sys.exit(0 if record['passed'] else 1)
