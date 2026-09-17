"""Two real guarded ServerLaunchers; controller relays, never computes replies.
All balances are synthetic. No network, campaign, research or persistence claims.
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
parser.add_argument('--label', default='m2')
parser.add_argument('--stage', choices=['core', 'negative', 'replay'], default='replay')
parser.add_argument('--maps', action='store_true', help='Separate generated-map ticking fixture (not campaign sectors)')
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.label)
os.chdir(ROOT)
BUILD = ROOT / 'build'
ENGINE = Path('/opt/mindustry/server-release.jar')
jar = (ROOT / args.jar).resolve()
assert jar.is_relative_to(BUILD)
start = time.monotonic()
deadline = start + 80  # includes compile/startup/exchange; cleanup <=4 seconds
record = dict(passed=False, scope='real two-engine stdio synthetic ledger', stage=args.stage,
              children=[], events=[], resources=[])
children = []
temps = []

def monitor():
    if time.monotonic() > deadline:
        raise TimeoutError('Entire test exceeded 80 seconds before cleanup')
    resource = check()
    resource['elapsed'] = round(time.monotonic()-start, 3)
    resource['pids_alive'] = [c.p.pid for c in children if c.p.poll() is None]
    record['resources'].append(resource)
    for c in children:
        if not c.stopped and c.p.poll() is not None:
            raise AssertionError(f'{c.name} unexpectedly exited {c.p.returncode}')

class Engine:
    def __init__(self, name):
        monitor()
        self.name, self.stopped, self.cursor = name, False, 0
        temp = tempfile.TemporaryDirectory(prefix='m2-'+name+'-', dir=BUILD)
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
               '-cp', str(BUILD / 'test-classes')+os.pathsep+str(ENGINE), 'fixture.TickingLauncher' if args.maps else 'fixture.SmokeLauncher']
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
    def state(self, balance, rev, unlocked):
        self.send('sc-m2-state')
        line = self.wait('SC_M2_STATE ')
        assert f'balance={balance} revision={rev} unlocked={str(unlocked).lower()}' in line, line
    def terminate(self):
        self.stopped = True
        stop(self.p)
        record['children'][children.index(self)]['exit'] = self.p.returncode


def relay(source, target, before_delivery=None):
    line = source.wait('SC_M2_FRAME ')
    frame = line[len('SC_M2_FRAME '):]
    assert len(frame.encode('utf-8')) <= 4096
    assert source.p.poll() is None and target.p.poll() is None
    record['events'].append(dict(kind='relay', source_pid=source.p.pid, target_pid=target.p.pid, frame=frame))
    if before_delivery is not None:
        before_delivery()
    target.send('sc-m2-recv '+frame, kind='relay')
    return frame

try:
    assert jar.exists(), 'Existing JAR required; missing artifact is not RED evidence'
    record['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    record['engine_sha256'] = hashlib.sha256(ENGINE.read_bytes()).hexdigest()
    run(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
         '--release', '17', '-cp', str(ENGINE), '-d', str(BUILD/'test-classes'),
         str(ROOT/'tests/SmokeLauncher.java')] + ([str(ROOT/'tests/TickingLauncher.java')] if args.maps else []), timeout=15)
    host = Engine('host')
    guest = Engine('guest')  # boot sequentially, then coexist
    assert host.p.pid != guest.p.pid
    assert len({c['jar_sha256'] for c in record['children']}) == 1
    for engine in (host, guest):
        engine.send('sc-m2-state')
        engine.wait('SC_M2_REJECT reason=inactive')
    host.send('sc-m2-start host session-m2')
    guest.send('sc-m2-start guest session-m2')
    host.wait('SC_M2_READY role=host session=session-m2 synthetic=true')
    guest.wait('SC_M2_READY role=guest session=session-m2 synthetic=true')
    host.state(100, 0, False)
    guest.state(-1, -1, False)
    def world(engine):
        engine.send('sc-fixture-world')
        line = engine.wait('SC_FIXTURE_WORLD ')
        fields = dict(part.split('=', 1) for part in line.split()[1:])
        assert fields['playing'] == 'true' and fields['campaign'] == 'false'
        assert fields['netActive'] == 'false'
        return fields
    if args.maps:
        for engine, ident, width in [(host, 'generated-host', 16), (guest, 'generated-guest', 20)]:
            engine.send(f'sc-fixture-load {ident} {width}')
            engine.wait('SC_FIXTURE_LOADED id='+ident)
        before = {e.name: world(e) for e in (host, guest)}
        assert before['host']['id'] != before['guest']['id']
        assert before['host']['width'] == '16' and before['guest']['width'] == '20'
    guest.send('sc-m2-request r1 test-tech')
    request = relay(guest, host, lambda: (guest.state(-1, -1, False), host.state(100, 0, False)))
    assert request == 'SC2|1|session-m2|r1|REQ|test-tech', request
    response = relay(host, guest, lambda: (host.state(70, 1, True), guest.state(-1, -1, False)))
    assert response == 'SC2|1|session-m2|r1|RES|1|70|true', response
    guest.wait('SC_M2_APPLIED request=r1 revision=1')
    host.state(70, 1, True)
    guest.state(70, 1, True)
    guest.send('sc-m2-request r1 test-tech')
    assert relay(guest, host) == request
    assert relay(host, guest) == response
    guest.wait('SC_M2_APPLIED request=r1 revision=1')
    host.state(70, 1, True)
    guest.state(70, 1, True)
    if args.maps:
        time.sleep(.4)
        after = {e.name: world(e) for e in (host, guest)}
        for e in (host, guest):
            assert after[e.name]['id'] == before[e.name]['id']
            assert after[e.name]['width'] == before[e.name]['width']
            assert float(after[e.name]['tick']) > float(before[e.name]['tick'])
            assert int(after[e.name]['updateId']) > int(before[e.name]['updateId'])
        record['worlds'] = dict(before=before, after=after, scope='generated maps; not campaign sectors')
    record['no_delivery_no_guest_mutation'] = True
    record['core'] = True
    if args.stage != 'core':
        def reject(engine, command, reason, balance, revision, unlocked):
            offset = len(engine.log.read_text())
            engine.send(command, kind='negative-input')
            engine.wait('SC_M2_REJECT reason='+reason)
            engine.state(balance, revision, unlocked)  # barrier: still alive, unchanged
            tail = engine.log.read_text()[offset:]
            assert 'SC_M2_FRAME ' not in tail and 'SC_M2_APPLIED ' not in tail, tail
            record.setdefault('negative_checks', []).append(dict(engine=engine.name, reason=reason, command=command))
        host_bad = [
            ('garbage', 'shape'),
            ('XX|1|session-m2|bad|REQ|test-tech', 'protocol'),
            ('SC2|2|session-m2|bad|REQ|test-tech', 'version'),
            ('SC2|1|wrong-session|bad|REQ|test-tech', 'session'),
            ('SC2|1|session-m2|bad!|REQ|test-tech', 'identifier'),
            ('SC2|1|session-m2|bad|REQ|test-tech|0', 'shape'), # guest price refused
            ('SC2|1|session-m2|bad|REQ|missing-tech', 'unknown-tech'),
            ('SC2|1|session-m2|r1|REQ|missing-tech', 'conflict'),
            ('SC2|1|session-m2|bad|RES|1|70|true', 'type'),
            ('x'*4097, 'size'),
            ('é'*2049, 'size'), # UTF-8 byte bound, not just character count
            ('SC2|1|session-m2|bad|REQ|'+'x'*(4096-len('SC2|1|session-m2|bad|REQ|')), 'identifier'), # exactly 4096 bytes
        ]
        assert len(host_bad[-1][0].encode()) == 4096
        for frame, reason in host_bad:
            reject(host, 'sc-m2-recv '+frame, reason, 70, 1, True)
        reject(host, 'sc-m2-start host other-session', 'already-active', 70, 1, True)
        reject(host, 'sc-m2-request bad test-tech', 'role', 70, 1, True)
        # Replay without a pending request must not look like a successful receipt.
        reject(guest, 'sc-m2-recv '+response, 'unexpected-id', 70, 1, True)
        guest.send('sc-m2-request pending test-tech')
        pending_request = guest.wait('SC_M2_FRAME ')
        record['pending_request'] = pending_request
        # Negative mutations only; controller never constructs a successful response.
        for frame, reason in [
            (response.replace('|r1|', '|unknown|'), 'unexpected-id'),
            (response.replace('session-m2', 'wrong-session'), 'session'),
            (response.replace('|1|session', '|2|session'), 'version'),
            ('SC2|1|session-m2|pending|REQ|test-tech', 'type'),
            ('SC2|1|session-m2|pending|RES|1|NaN|true', 'value'),
            ('SC2|1|session-m2|pending|RES|1|71|true', 'revision-conflict'),
            ('SC2|1|session-m2|pending|RES|0|70|true', 'stale'),
        ]:
            reject(guest, 'sc-m2-recv '+frame, reason, 70, 1, True)
        reject(guest, 'sc-m2-request r1 different-tech', 'conflict', 70, 1, True)
        record['negative'] = True
    if args.stage == 'replay':
        guest.send('sc-m2-request r2 test-tech-2')
        request2 = relay(guest, host)
        response2 = relay(host, guest)
        assert response2 == 'SC2|1|session-m2|r2|RES|2|60|true'
        guest.wait('SC_M2_APPLIED request=r2 revision=2')
        host.state(60, 2, True)
        guest.state(60, 2, True)
        # Real old engine response, not a fabricated stale response.
        guest.send('sc-m2-request r1 test-tech')
        assert relay(guest, host) == request
        assert relay(host, guest) == response
        guest.wait('SC_M2_REJECT reason=stale')
        guest.state(60, 2, True)
        host.state(60, 2, True)
        guest.terminate()
        time.sleep(.3)
        host.state(60, 2, True)
        record['host_survived_guest_termination'] = True
        guest2 = Engine('guest-restarted')
        guest2.send('sc-m2-start guest session-m2')
        guest2.wait('SC_M2_READY role=guest session=session-m2 synthetic=true')
        guest2.state(-1, -1, False)
        for rid, tech, expected, rev, bal in [('r1', 'test-tech', response, 1, 70),
                                               ('r2', 'test-tech-2', response2, 2, 60)]:
            guest2.send(f'sc-m2-request {rid} {tech}')
            relay(guest2, host)
            assert relay(host, guest2) == expected
            guest2.wait(f'SC_M2_APPLIED request={rid} revision={rev}')
            guest2.state(bal, rev, True)
            host.state(60, 2, True)
        record['restart_replay'] = True
    record['passed'] = True
except Exception as e:
    record['error'] = f'{type(e).__name__}: {e}'
finally:
    for c in children:
        c.terminate()
        c.p.stdin.close()
        c.f.close()
    for t in temps:
        t.cleanup()
    record['elapsed_seconds'] = round(time.monotonic()-start, 3)
    (BUILD/f'{args.label}-result.json').write_text(json.dumps(record, indent=2)+'\n')
print(json.dumps({k:v for k,v in record.items() if k not in ('events', 'resources', 'children', 'negative_checks')}, indent=2))
sys.exit(0 if record['passed'] else 1)
