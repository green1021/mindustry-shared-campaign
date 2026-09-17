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
parser.add_argument('--label', default='m4')
parser.add_argument('--stage', choices=['f1', 'f2'], default='f2')
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.label)
os.chdir(ROOT)
BUILD = ROOT / 'build'
ENGINE = Path('/opt/mindustry/server-release.jar')
jar = (ROOT / args.jar).resolve()
assert jar.is_relative_to(BUILD)
start = time.monotonic()
deadline = start + 80  # includes compile/startup/exchange; cleanup <=4 seconds per owned child (two maximum)
record = dict(passed=False, scope='host sector ownership and actual isolated SaveIO; local stdio', stage=args.stage,
              children=[], events=[], resources=[])
children = []
temps = []

def monitor():
    if time.monotonic() > deadline:
        raise TimeoutError('Entire test exceeded 80 seconds before cleanup')
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
               '-cp', str(BUILD / 'test-classes')+os.pathsep+str(ENGINE), 'fixture.SectorSessionLauncher']
        self.p = subprocess.Popen(cmd, cwd=d, env=env, stdin=subprocess.PIPE,
                                  stdout=self.f, stderr=subprocess.STDOUT, text=True)
        children.append(self)
        record['children'].append(dict(name=name, pid=self.p.pid, jar_sha256=digest, command=cmd))
        self.wait('Server loaded.', timeout=18)
        text = re.sub(r'\x1b\[[0-9;]*m', '', self.log.read_text())
        assert 'SC_NETWORK_DENIED' in text and 'SC_PC_INIT_OK engine=160.4' in text and '1 mods loaded.' in text
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

def world(e, sector, copper):
    line = command(e, 'sc-fixture-campaign-state', 'SC_CAMPAIGN_STATE ')
    f = dict(x.split('=', 1) for x in line.split()[1:])
    assert f['campaign'] == f['playing'] == f['core'] == 'true', f
    assert f['netActive'] == 'false' and f['sector'] == str(sector) and f['copper'] == str(copper), f
    return f

def relay(a, b):
    frame = a.wait('SC_M4_FRAME ').removeprefix('SC_M4_FRAME ')
    record['events'].append(dict(kind='relay', source_pid=a.p.pid, target_pid=b.p.pid, frame=frame))
    b.send('sc-m4-recv '+frame, kind='relay')
    return frame

def reject(e, text, reason):
    command(e, text, 'SC_M4_REJECT reason='+reason)
    record.setdefault('negatives', []).append(dict(command=text, reason=reason))
    world(host, 170, 100)

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
         str(ROOT/'tests/SectorSessionLauncher.java')], timeout=15)
    host = Engine('host')
    command(host, 'sc-fixture-directory', 'SC_FIXTURE_DIRECTORY ', 18)
    before = world(host, 170, 100)
    record['before'] = before
    # F1 RED reaches a loaded real host campaign with an actual Frozen Forest save.
    command(host, 'sc-m4-start host session-m4', 'SC_M4_READY role=host')
    turn = command(host, 'sc-fixture-turn-probe', 'SC_FIXTURE_TURN ')
    fields = dict(x.split('=') for x in turn.split()[1:])
    assert fields['before'] == fields['after']
    record['offline_turn_probe'] = turn
    guest = Engine('guest')
    command(guest, 'sc-m4-start guest session-m4', 'SC_M4_READY role=guest')
    if args.stage == 'f2':
        reject(guest, 'sc-m4-open', 'not-selected')
        reject(guest, 'sc-m4-save', 'not-loaded')
    guest.send('sc-m4-list list1')
    relay(guest, host)
    directory = relay(host, guest)
    command(guest, 'sc-m4-directory', 'SC_M4_DIRECTORY ')
    assert '|campaign-fixture|' in directory and 'serpulo:170:host-active' in directory
    assert 'serpulo:64:owned-saved' in directory and ':new-locked' in directory, directory
    entries = directory.split('|')[-1].split(',')
    locked = next(e.split(':')[:2] for e in entries if e.endswith(':new-locked'))
    reject(host, 'sc-m4-recv SC4|1|session-m4|lock|SELECT|campaign-fixture|'+'|'.join(locked), 'ineligible')
    selection = 'SC4|1|session-m4|choose1|SELECT|campaign-fixture|serpulo|64'
    for frame, reason in [(selection.replace('session-m4', 'wrong'), 'session'),
                          (selection.replace('campaign-fixture', 'other'), 'campaign'),
                          (selection.replace('|64', '|170'), 'host-active'),
                          (selection.replace('|64', '|99999'), 'unknown-sector'),
                          (selection+'|extra', 'shape'), ('x'*4097, 'size'),
                          (selection.replace('SC4|1', 'SC4|2'), 'version'),
                          (selection.replace('SC4', 'XX'), 'protocol'),
                          (selection.replace('|SELECT|', '|GRANT|'), 'type'),
                          (selection.replace('campaign-fixture', '../campaign-fixture'), 'campaign'),
                          ('é'*2049, 'size'),
                          (selection.replace('|choose1|', '|../bad|'), 'identifier')]:
        reject(host, 'sc-m4-recv '+frame, reason)
    guest.send('sc-m4-select choose1 campaign-fixture serpulo 64')
    assert relay(guest, host) == selection
    if args.stage == 'f2':
        command(guest, 'sc-m4-open', 'SC_M4_REJECT reason=not-selected')  # leave host grant unread until relay
    grant = relay(host, guest)
    guest.wait('SC_M4_GRANTED ')
    # Queue conflicting requests without waiting; host engine thread serializes admission.
    host.send('sc-m4-recv '+selection.replace('|choose1|', '|contender1|'))
    host.send('sc-m4-recv '+selection.replace('|choose1|', '|contender2|'))
    host.wait('SC_M4_REJECT reason=owned')
    host.wait('SC_M4_REJECT reason=owned')
    reject(host, 'sc-m4-recv '+selection.replace('|64', '|170'), 'conflict')
    guest.send('sc-m4-select choose1 campaign-fixture serpulo 64')
    assert relay(guest, host) == selection
    assert relay(host, guest) == grant
    guest.wait('SC_M4_GRANTED ')
    record['f1'] = dict(directory=directory, selection=selection, grant=grant, replay_identical=True, conflicting_admissions=2)
    if args.stage == 'f2':
        # F2 RED must invoke production open/save, not a JSON persistence surrogate.
        command(guest, 'sc-m4-open', 'SC_M4_OPENED ', 18)
        record['guest_before'] = world(guest, 64, 37)
        reject(guest, 'sc-m4-open', 'already-loaded')
        command(host, 'sc-fixture-lock-probe', 'SC_FIXTURE_LOCK_BLOCKED')
        command(guest, 'sc-fixture-seed-local 53', 'SC_FIXTURE_SEEDED ')
        path = workspace/'campaigns/campaign-fixture/serpulo-64.msav'
        # Confined symlink fault: store rejects it before any overwrite, even inside workspace.
        decoy = path.with_suffix('.decoy')
        decoy.write_bytes(b'untouched')
        parked = path.with_suffix('.parked')
        path.rename(parked)
        try:
            path.symlink_to(decoy)
            reject(guest, 'sc-m4-save', 'symlink')
            assert decoy.read_bytes() == b'untouched'
        finally:
            path.unlink()
            parked.rename(path)
        initial_hash = hashlib.sha256(path.read_bytes()).hexdigest()
        saved = command(guest, 'sc-m4-save', 'SC_M4_SAVED ', 12)
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        assert len(data) > 1000 and digest != initial_hash and digest in saved, saved
        command(host, 'sc-m4-save', 'SC_M4_SAVED ', 12)
        host_path = workspace/'campaigns/campaign-fixture/serpulo-170.msav'
        host_hash = hashlib.sha256(host_path.read_bytes()).hexdigest()
        assert host_hash != digest
        # Second save exercises atomic replacement of an existing file.
        saved = command(guest, 'sc-m4-save', 'SC_M4_SAVED ', 12)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest in saved
        record['saves'] = dict(guest_sha256=digest, host_sha256=host_hash, guest_bytes=path.stat().st_size,
                               host_bytes=host_path.stat().st_size, initial_guest_sha256=initial_hash)
        for file in (path, host_path):
            shutil.copyfile(file, BUILD/(args.label+'-'+file.name))
        record['guest_saved'] = world(guest, 64, 53)
        host_mid = world(host, 170, 100)
        guest.terminate()
        replacement = Engine('replacement')
        command(replacement, 'sc-m4-start guest session-m4', 'SC_M4_READY role=guest')
        replacement.send('sc-m4-select choose1 campaign-fixture serpulo 64')
        assert relay(replacement, host) == selection
        assert relay(host, replacement) == grant
        replacement.wait('SC_M4_GRANTED ')
        command(replacement, 'sc-m4-open', 'SC_M4_OPENED ', 18)
        record['guest_reloaded'] = world(replacement, 64, 53)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest
        assert hashlib.sha256(host_path.read_bytes()).hexdigest() == host_hash
        time.sleep(.3)
        final_guest = world(replacement, 64, 53)
        assert int(final_guest['updateId']) > int(record['guest_reloaded']['updateId'])
        record['host_mid'] = host_mid
        record['guest_final'] = final_guest
        record['f2'] = True
    after = world(host, 170, 100)
    assert float(after['tick']) > float(before['tick']) and int(after['updateId']) > int(before['updateId'])
    record['after'] = after
    assert len({c['jar_sha256'] for c in record['children']}) == 1
    assert max(len(r['pids_alive']) for r in record['resources']) == 2
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
