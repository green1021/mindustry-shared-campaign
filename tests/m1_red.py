"""RED check for M1: shared campaign state test must fail before implementation."""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = Path('/opt/mindustry/server-release.jar')
CP = ROOT / 'build/test-classes'
CP.mkdir(parents=True, exist_ok=True)
report = ROOT / 'build/m1-red.json'
record = {'red': False}
try:
    run = subprocess.run(
        ['nice', '-n', '10', 'java', '-Xmx96m', '-XX:ActiveProcessorCount=1',
         '-cp', str(CP) + ':' + str(ENGINE), 'fixture.SharedStateTest'],
        capture_output=True, text=True, timeout=60)
    record['exit'] = run.returncode
    record['stdout'] = run.stdout[-800:]
    record['stderr'] = run.stderr[-800:]
    record['red'] = run.returncode != 0 and 'Missing shared campaign state implementation' in run.stdout + run.stderr
finally:
    report.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2))
sys.exit(0 if record['red'] else 1)
