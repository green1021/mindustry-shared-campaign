"""Build an obsolete-marker fixture; require the real-engine assertion to fail."""
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from resources import run

fixture = ROOT / 'build/negative-fixture'
fixture.mkdir(parents=True, exist_ok=True)
source = (ROOT / 'src/sc/SharedCampaignMod.java').read_text()
assert source.count('SC_PC_INIT_OK engine=160.4') == 1
(fixture / 'SharedCampaignMod.java').write_text(source.replace('SC_PC_INIT_OK engine=160.4', 'SC_PC_INIT_OLD engine=160.4'))
run(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1', '--release', '17',
     '-cp', '/opt/mindustry/server-release.jar', '-d', str(fixture), str(fixture / 'SharedCampaignMod.java')], timeout=20)
run(['nice', '-n', '10', 'jar', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1', '--create', '--no-manifest',
     '--file', str(ROOT / 'build/negative-fixture.jar'), '-C', str(fixture), 'sc/SharedCampaignMod.class',
     '-C', str(ROOT), 'mod.json'], timeout=10)
p = subprocess.run([sys.executable, str(ROOT / 'tests/smoke.py'), '--jar', 'build/negative-fixture.jar',
                    '--label', 'negative'], cwd=ROOT, timeout=58)
result = json.loads((ROOT / 'build/negative-result.json').read_text())
assert p.returncode == 1, 'Obsolete marker must fail smoke'
assert result.get('exit') == 0, 'Negative fixture must load and exit normally'
assert result.get('error') == 'Real mod init/version marker missing', result
assert 'SC_PC_INIT_OLD engine=160.4' in (ROOT / 'build/negative.log').read_text()
print('NEGATIVE_ASSERTION_CONFIRMED smoke_exit=1 engine_exit=0')
