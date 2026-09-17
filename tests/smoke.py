"""Bounded real-engine mod loading test. No live config or network access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'scripts'))
from resources import check, run as run_checked, stop

os.chdir(ROOT)
parser = argparse.ArgumentParser()
parser.add_argument('--jar', default='build/shared-campaign-pc.jar')
parser.add_argument('--label', default='smoke', choices=['smoke', 'negative'])
args = parser.parse_args()
JAR = (ROOT / args.jar).resolve()
assert JAR.is_relative_to(ROOT / 'build'), 'Fixture must be inside project build'
ENGINE = Path('/opt/mindustry/server-release.jar')
REPORT = ROOT / f'build/{args.label}-result.json'
LOG = ROOT / f'build/{args.label}.log'
ROOT.joinpath('build').mkdir(exist_ok=True)
record = {'passed': False, 'scope': 'headless PC engine mod loading only; no shared campaign or GUI verification'}
try:
    assert JAR.exists(), 'Expected built mod JAR is missing'
    record['resources_before'] = check()
    record.update(jar_sha256=hashlib.sha256(JAR.read_bytes()).hexdigest(),
                  engine_sha256=hashlib.sha256(ENGINE.read_bytes()).hexdigest())
    cp = ROOT / 'build/test-classes'
    cp.mkdir(exist_ok=True)
    run_checked(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
                 '--release', '17', '-cp', str(ENGINE), '-d', str(cp), 'tests/SmokeLauncher.java'], timeout=20)
    with tempfile.TemporaryDirectory(prefix='smoke-', dir=ROOT / 'build') as d:
        run = Path(d)
        mods = run / 'config/mods'
        mods.mkdir(parents=True)
        shutil.copyfile(JAR, mods / JAR.name)
        cmd = ['nice', '-n', '10', 'java', '-Xms32m', '-Xmx160m', '-XX:ActiveProcessorCount=1',
               '-Djava.awt.headless=true', '-Dorg.jline.terminal.provider=dumb',
               '-Dorg.jline.terminal.encoding=UTF-8', '-Dorg.jline.terminal.dumb.color=false',
               '-Duser.home=' + d, '-Djava.io.tmpdir=' + d,
               '-Dsmoke.root=' + d, '-cp', str(cp) + os.pathsep + str(ENGINE), 'fixture.SmokeLauncher']
        cmd[0] = shutil.which('nice')
        cmd[3] = shutil.which('java')
        cmd.insert(4, '-Dorg.jline.terminal.type=dumb')
        # JLine probes PATH with File.canExecute, which triggers checkExec.
        # No PATH avoids probing without granting subprocess permissions.
        env = os.environ.copy()
        env.pop('PATH', None)
        env['TERM'] = 'dumb'
        record['command'] = cmd
        check()
        with LOG.open('w') as f:
            p = subprocess.Popen(cmd, cwd=run, env=env, stdin=subprocess.PIPE, stdout=f, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 30
                commands_sent = False
                while p.poll() is None:
                    record['resources_last'] = check()
                    text = LOG.read_text()
                    if not commands_sent and 'Server loaded.' in text:
                        p.stdin.write('sc-status\nexit\n')
                        p.stdin.flush()
                        commands_sent = True
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Engine exceeded 30 seconds')
                    time.sleep(.2)
                record['exit'] = p.returncode
            finally:
                stop(p)
                p.stdin.close()
        text = re.sub(r'\x1b\[[0-9;]*m', '', LOG.read_text())
        assert p.returncode == 0, 'Engine did not exit cleanly'
        assert 'SC_NETWORK_DENIED' in text, 'Network guard missing'
        assert '1 mods loaded.' in text, 'Mod loader evidence missing'
        assert 'SC_PC_INIT_OK engine=160.4' in text, 'Real mod init/version marker missing'
        assert 'SC_STATUS version=0.0.2 engine=160.4 features=false' in text, 'Server command status missing'
        record['passed'] = True
except Exception as e:
    record['error'] = str(e)
REPORT.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2))
sys.exit(0 if record['passed'] else 1)
