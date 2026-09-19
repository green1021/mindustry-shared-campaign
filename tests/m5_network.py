"""Two real guarded ServerLaunchers; controller relays, never computes replies.
M4 host admission and same-sector SaveIO integration. Fixtures never packaged in production.
"""
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
from resources import check, run, stop

parser = argparse.ArgumentParser()
parser.add_argument('--jar', default='build/shared-campaign-pc.jar')
parser.add_argument('--label', default='m5')
parser.add_argument('--stage', choices=['f1', 'f2', 'f3'], default='f3')
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.label)
os.chdir(ROOT)
BUILD = ROOT / 'build'
ENGINE = Path('/opt/mindustry/server-release.jar')
jar = (ROOT / args.jar).resolve()
assert jar.is_relative_to(BUILD)
start = time.monotonic()
deadline = start + 108  # includes compile/startup/exchange; cleanup <=4 seconds per owned child (two maximum)
record = dict(passed=False, scope='real engine ArcNet localhost transport, no controller byte relay', stage=args.stage,
              children=[], events=[], resources=[])
children = []
temps = []

def monitor():
    if time.monotonic() > deadline:
        raise TimeoutError('Entire test exceeded 108 seconds before cleanup')
    resource = check()
    resource['elapsed'] = round(time.monotonic()-start, 3)
    resource['pids_alive'] = [c.p.pid for c in children if c.p.poll() is None]
    resource['rss_kib'] = {str(c.p.pid): next((int(l.split()[1]) for l in Path(f'/proc/{c.p.pid}/status').read_text().splitlines() if l.startswith('VmRSS:')), 0) for c in children if c.p.poll() is None}
    record['resources'].append(resource)
    for c in children:
        if not c.stopped and c.p.poll() is not None:
            raise AssertionError(f'{c.name} unexpectedly exited {c.p.returncode}')

class Engine:
    def __init__(self, name):
        monitor()
        self.name, self.stopped, self.cursor = name, False, 0
        temp = tempfile.TemporaryDirectory(prefix='m4-'+name+'-', dir=workspace)
        temps.append(temp)
        d = Path(temp.name)
        mods = d / 'config/mods'
        mods.mkdir(parents=True)
        shutil.copyfile(jar, mods / jar.name)
        digest = hashlib.sha256((mods / jar.name).read_bytes()).hexdigest()
        self.log = BUILD / f'{args.label}-{name}.log'
        self.f = self.log.open('w')
        env = os.environ.copy()
        env.pop('PATH', None)
        env['TERM'] = 'dumb'
        cmd = [shutil.which('nice'), '-n', '10', shutil.which('java'),
               '-Xms24m', '-Xmx128m', '-XX:ActiveProcessorCount=1',
               '-Djava.awt.headless=true', '-Dorg.jline.terminal.provider=dumb',
               '-Dorg.jline.terminal.type=dumb', '-Dorg.jline.terminal.encoding=UTF-8',
               '-Dorg.jline.terminal.dumb.color=false', '-Duser.home='+str(d),
               '-Djava.io.tmpdir='+str(d), '-Dsmoke.root='+str(d),
               '-Dsc.m4.workspace='+str(workspace), '-Dsc.m4.root='+str(workspace/'campaigns'),
               '-cp', str(BUILD / 'test-classes')+os.pathsep+str(ENGINE), 'fixture.NetworkLauncher']
        self.p = subprocess.Popen(cmd, cwd=d, env=env, stdin=subprocess.PIPE,
                                  stdout=self.f, stderr=subprocess.STDOUT, text=True)
        children.append(self)
        record['children'].append(dict(name=name, pid=self.p.pid, jar_sha256=digest, command=cmd))
        self.wait('Server loaded.', timeout=18)
        text = re.sub(r'\x1b\[[0-9;]*m', '', self.log.read_text())
        assert 'SC_NETWORK_LOOPBACK_GUARD' in text and 'SC_PC_INIT_OK engine=160.4' in text and '1 mods loaded.' in text
    def send(self, cmd, kind='command'):
        monitor()
        record['events'].append(dict(engine=self.name, direction='stdin', kind=kind, text=cmd))
        self.p.stdin.write(cmd+'\n')
        self.p.stdin.flush()
    def wait(self, marker, timeout=4):
        end = min(deadline, time.monotonic()+timeout)
        while time.monotonic() < end:
            monitor()
            lines = re.sub(r'\x1b\[[0-9;]*m', '', self.log.read_text()).splitlines()
            while self.cursor < len(lines):
                line = lines[self.cursor]
                self.cursor += 1
                record['events'].append(dict(engine=self.name, direction='stdout', text=line))
                if marker in line:
                    return line[line.index(marker):]
            time.sleep(.08)
        raise AssertionError(f'{self.name}: missing {marker!r}; see {self.log.name}')
    def terminate(self):
        self.stopped = True
        stop(self.p)
        record['children'][children.index(self)]['exit'] = self.p.returncode


def command(e, text, marker, timeout=4):
    e.send(text)
    return e.wait(marker, timeout)

def request(e, rid, op, expected):
    # Status is special: it doesn't wait for 'expected', it just sends then waits for status RX
    if op == 'STATUS':
        e.send('sc-m5-send SC5|1|session-m5|'+rid+'|'+op)
        return e.wait('SC_M5_RX SC5|1|session-m5|'+rid+'|STATUS', 8)
    e.send('sc-m5-send SC5|1|session-m5|'+rid+'|'+op)
    return e.wait('SC_M5_RX SC5|1|session-m5|'+rid+'|'+expected, 8)

def world(e, sector, copper):
    line=command(e, 'sc-fixture-campaign-state', 'SC_CAMPAIGN_STATE ')
    f=dict(x.split('=',1) for x in line.split()[1:])
    assert f['sector']==str(sector) and f['copper']==str(copper) and f['campaign']=='true', f
    return f

try:
    assert jar.exists()
    temp = tempfile.TemporaryDirectory(prefix='m4-workspace-', dir=BUILD)
    temps.append(temp)
    workspace = Path(temp.name)
    (workspace/'.sc-m4-disposable').write_text('M4 disposable only\n')
    (workspace/'campaigns').mkdir()
    record['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    record['engine_sha256'] = hashlib.sha256(ENGINE.read_bytes()).hexdigest()
    run(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
         '--release', '17', '-cp', str(ENGINE), '-d', str(BUILD/'test-classes'),
         str(ROOT/'tests/NetworkLauncher.java')], timeout=15)
    host = Engine('host')
    command(host, 'sc-fixture-directory', 'SC_FIXTURE_DIRECTORY ', 18)
    guest = Engine('guest')
    port = 41000 + os.getpid() % 900
    command(host, f'sc-m5-start host session-m5 {port}', 'SC_M5_LISTEN ', 6)
    command(guest, f'sc-m5-start guest session-m5 {port}', 'SC_M5_CONNECTED ', 8)
    command(host, 'sc-m5-state', 'SC_M5_STATE role=host active=true server=true client=false')
    command(guest, 'sc-m5-state', 'SC_M5_STATE role=guest active=true server=false client=true')
    guest.send('sc-m5-send SC5|1|session-m5|ping1|PING')
    guest.wait('SC_M5_RX SC5|1|session-m5|ping1|PONG')
    record['f1'] = True
    if args.stage != 'f1':
        before=world(host,170,100)
        directory=request(guest,'list1','LIST','DIRECTORY|campaign-fixture|')
        assert 'serpulo:64:owned-saved' in directory and 'serpulo:170:host-active' in directory and ':new-locked' in directory
        request(guest,'choose','SELECT|campaign-fixture|serpulo|64','GRANT|campaign-fixture|serpulo|64')
        request(guest,'conflict','SELECT|campaign-fixture|serpulo|64','REJECT|owned')
        request(guest,'unknown','SELECT|campaign-fixture|serpulo|99999','REJECT|unknown-sector')
        request(guest,'wrong','SELECT|wrong|serpulo|64','REJECT|campaign')
        guest.send('sc-m5-send SC5|2|session-m5|version|LIST')
        guest.wait('SC_M5_RX SC5|1|session-m5|version|REJECT|version')
        command(guest,'sc-m5-open','SC_M5_OPENED ',18)
        record['guest_before']=world(guest,64,37)
        command(guest,'sc-fixture-seed-local 53','SC_FIXTURE_SEEDED ')
        command(guest,'sc-m5-save','SC_M5_SAVED ',12)
        record['guest_saved']=world(guest,64,53)
        guest.stopped=True
        guest.p.kill();guest.p.wait(timeout=3)
        record['children'][children.index(guest)]['exit']=guest.p.returncode
        host.wait('SC_M5_RELEASED sector=serpulo:64',8)
        replacement=Engine('replacement')
        command(replacement,f'sc-m5-start guest session-m5 {port}','SC_M5_CONNECTED ',8)
        request(replacement,'newowner','SELECT|campaign-fixture|serpulo|64','GRANT|campaign-fixture|serpulo|64')
        command(replacement,'sc-m5-open','SC_M5_OPENED ',18)
        record['guest_reloaded']=world(replacement,64,53)
        if args.stage == 'f3':
            # Verification of Status Sync
            status1 = request(replacement,'res-status','STATUS','STATUS|')
            assert 'copper' in status1
            
            # Resource transfer: guest requests 15 copper from host core
            request(replacement,'res-tx','TRANSFER|copper|15','TRANSFERRED|copper|15')
            world(host,170,85) # Host copper was 100, now 85
            
            request(replacement,'prereq','RESEARCH|thorium-reactor','REJECT|locked-prerequisite')
            request(replacement,'res1','RESEARCH|mechanical-drill','UNLOCKED|mechanical-drill')
            world(host,170,75) # Host copper deducted from 85 -> 75 for mechanical drill
            
            # Post-research status check
            status2 = request(replacement,'res-status-2','STATUS','STATUS|')
            assert 'copper' in status2
            
            record['f3']=True
        command(replacement,'sc-m5-disconnect','SC_M5_DISCONNECTED local=true')
        host.wait('SC_M5_RELEASED sector=serpulo:64',8)
        after=world(host,170,75) # Copper is now 75
        assert float(after['tick'])>float(before['tick']) and int(after['updateId'])>int(before['updateId'])
        record['host_before'],record['host_after']=before,after
        record['f2']=True
    record['passed'] = True
except Exception as e:
    record['error'] = f'{type(e).__name__}: {e}'
finally:
    for c in children:
        c.terminate()
        c.p.stdin.close()
        c.f.close()
    for t in reversed(temps):
        t.cleanup()
    record['elapsed_seconds'] = round(time.monotonic()-start, 3)
    (BUILD/f'{args.label}-result.json').write_text(json.dumps(record, indent=2)+'\n')
print(json.dumps({k:v for k,v in record.items() if k not in ('events', 'resources', 'children', 'negatives')}, indent=2))
sys.exit(0 if record['passed'] else 1)
