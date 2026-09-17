"""Two real guarded ServerLaunchers; controller relays, never computes replies.
M3 campaign/core integration. Fixtures never packaged in production.
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
parser.add_argument('--label', default='m3')
parser.add_argument('--stage', choices=['f1', 'f2', 'f3'], default='f3')
parser.add_argument('--two-sectors', action='store_true', help='Load bundled Frozen Forest in guest; never seed/copy host inventory')
parser.add_argument('--baseline', action='store_true', help='Use pre-F1 launcher for expected RED')
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.label)
os.chdir(ROOT)
BUILD = ROOT / 'build'
ENGINE = Path('/opt/mindustry/server-release.jar')
jar = (ROOT / args.jar).resolve()
assert jar.is_relative_to(BUILD)
start = time.monotonic()
deadline = start + 80  # includes compile/startup/exchange; cleanup <=4 seconds per owned child (two maximum)
record = dict(passed=False, scope='real campaign/core inventory; local stdio', stage=args.stage, two_sectors_requested=args.two_sectors,
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
        temp = tempfile.TemporaryDirectory(prefix='m3-'+name+'-', dir=BUILD)
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
               '-cp', str(BUILD / 'test-classes')+os.pathsep+str(ENGINE), 'fixture.SmokeLauncher' if args.baseline else 'fixture.CampaignLauncher']
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


def relay(source, target, before_delivery=None):
    line = source.wait('SC_M3_FRAME ')
    frame = line[len('SC_M3_FRAME '):]
    assert len(frame.encode()) <= 4096
    assert source.p.poll() is None and target.p.poll() is None
    if before_delivery:
        before_delivery()
    record['events'].append(dict(kind='relay', source_pid=source.p.pid, target_pid=target.p.pid, frame=frame))
    target.send('sc-m3-recv '+frame, kind='relay')
    return frame

def host_copper(engine, expected):
    fields = world(engine)
    assert int(fields['copper']) == expected, fields
    return fields

def guest_state(engine, copper, revision, sector):
    engine.send('sc-m3-state')
    line = engine.wait('SC_M3_STATE ')
    assert f'role=guest sector={sector} copper={copper} revision={revision} authority=cache-only' in line, line


def world(engine, preset="groundZero"):
    engine.send('sc-fixture-campaign-state')
    line = engine.wait('SC_CAMPAIGN_STATE ')
    fields = dict(part.split('=', 1) for part in line.split()[1:])
    assert fields['campaign'] == 'true' and fields['playing'] == 'true', fields
    assert fields['planet'] == 'serpulo', fields
    if preset == 'groundZero':
        assert fields['sector'] == '170', fields
    assert fields['preset'] == preset and fields['core'] == 'true', fields
    assert int(fields['width']) > 16 and int(fields['height']) > 16
    assert fields['netActive'] == 'false'
    return fields

try:
    assert jar.exists(), 'Existing JAR required'
    record['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    record['engine_sha256'] = hashlib.sha256(ENGINE.read_bytes()).hexdigest()
    run(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
         '--release', '17', '-cp', str(ENGINE), '-d', str(BUILD/'test-classes'),
         str(ROOT/'tests/SmokeLauncher.java')] + ([] if args.baseline else [str(ROOT/'tests/CampaignLauncher.java')]), timeout=15)
    host = Engine('host')
    host.send('sc-fixture-campaign-load')
    host.wait('SC_CAMPAIGN_LOADED ', timeout=18)
    before = world(host)
    time.sleep(.4)
    after = world(host)
    assert float(after['tick']) > float(before['tick'])
    assert int(after['updateId']) > int(before['updateId'])
    record['worlds'] = dict(before=before, after=after)
    record['f1'] = True
    if args.stage in ('f2', 'f3'):
        host.send('sc-m3-start host session-m3')
        host.wait('SC_M3_READY role=host session=session-m3 sector=serpulo:170')
        host.send('sc-fixture-seed')
        host.wait('SC_FIXTURE_SEEDED testOnly=true before=0 after=100')
        assert world(host)['copper'] == '100'
        request = 'SC3|1|session-m3|r1|REQ|serpulo|170|reserve-copper'
        host.send('sc-m3-recv '+request)
        response = host.wait('SC_M3_FRAME ')[len('SC_M3_FRAME '):]
        assert response == 'SC3|1|session-m3|r1|RES|serpulo|170|reserve-copper|1|70', response
        assert world(host)['copper'] == '70'
        host.send('sc-m3-recv '+request)
        assert host.wait('SC_M3_FRAME ')[len('SC_M3_FRAME '):] == response
        assert world(host)['copper'] == '70'
        bad = [
            ('garbage', 'shape'),
            (request.replace('SC3', 'XX'), 'protocol'),
            (request.replace('|1|session', '|2|session'), 'version'),
            (request.replace('session-m3', 'wrong'), 'session'),
            (request.replace('|r1|', '|bad!|'), 'identifier'),
            (request.replace('|170|', '|171|'), 'sector'),
            (request.replace('|serpulo|', '|erekir|'), 'sector'),
            (request+'|0', 'shape'),
            (request.replace('|r1|', '|bad|').replace('reserve-copper', 'unknown'), 'catalog'),
            (request.replace('reserve-copper', 'reserve-large'), 'conflict'),
            (request.replace('|REQ|', '|RES|'), 'type'),
            ('x'*4097, 'size'), ('é'*2049, 'size'),
            (request.replace('|r1|', '|poor|').replace('reserve-copper', 'reserve-large'), 'funds'),
        ]
        for frame, reason in bad:
            offset = len(host.log.read_text())
            host.send('sc-m3-recv '+frame, kind='negative-input')
            host.wait('SC_M3_REJECT reason='+reason)
            assert world(host)['copper'] == '70'
            assert 'SC_M3_FRAME ' not in host.log.read_text()[offset:]
            record.setdefault('negative_checks', []).append(dict(reason=reason, frame=frame))
        record['f2'] = dict(request=request, response=response, before=100, after=int(world(host)['copper']), negatives=len(bad))
    if args.stage == 'f3':
        guest = Engine('guest')
        assert guest.p.pid != host.p.pid
        assert len({c['jar_sha256'] for c in record['children']}) == 1
        guest.send('sc-m3-start guest session-m3')
        guest.wait('SC_M3_READY role=guest session=session-m3 sector=unknown')
        guest.send('sc-fixture-campaign-state')
        menu = guest.wait('SC_CAMPAIGN_STATE ')
        assert 'campaign=false' in menu and 'core=false' in menu and 'playing=false' in menu, menu
        record['guest_world'] = menu
        if args.two_sectors:
            guest.send('sc-fixture-frozen-load')
            guest.wait('SC_CAMPAIGN_LOADED preset=frozenForest', timeout=18)
            guest_before = world(guest, 'frozenForest')
            assert guest_before['sector'] != world(host)['sector']
        guest_state(guest, -1, -1, 'unknown')
        exchange_before = world(host)
        guest.send('sc-m3-request r1 serpulo 170 reserve-copper')
        assert relay(guest, host, lambda: guest_state(guest, -1, -1, 'unknown')) == request
        assert relay(host, guest, lambda: (guest_state(guest, -1, -1, 'unknown'), world(host))) == response
        guest.wait('SC_M3_APPLIED request=r1 revision=1')
        guest_state(guest, 70, 1, 'serpulo:170')
        assert world(host)['copper'] == '70'
        guest.send('sc-m3-request r2 serpulo 170 reserve-copper')
        request2 = relay(guest, host, lambda: host_copper(host, 70))
        response2 = relay(host, guest, lambda: (host_copper(host, 40), guest_state(guest, 70, 1, 'serpulo:170')))
        assert response2 == 'SC3|1|session-m3|r2|RES|serpulo|170|reserve-copper|2|40'
        guest.wait('SC_M3_APPLIED request=r2 revision=2')
        guest_state(guest, 40, 2, 'serpulo:170')
        assert world(host)['copper'] == '40'
        guest.send('sc-m3-request r2 serpulo 170 reserve-copper')
        assert relay(guest, host) == request2
        assert relay(host, guest) == response2
        guest.wait('SC_M3_APPLIED request=r2 revision=2')
        assert world(host)['copper'] == '40'
        # Old successful bytes came from the host, never manufactured by the controller.
        guest.send('sc-m3-request r1 serpulo 170 reserve-copper')
        assert relay(guest, host) == request
        assert relay(host, guest) == response
        guest.wait('SC_M3_REJECT reason=stale')
        guest_state(guest, 40, 2, 'serpulo:170')
        for frame, reason in [(response2, 'unexpected-id'),
                              (response.replace('|170|', '|171|'), 'sector'),
                              (response.replace('session-m3', 'wrong'), 'session'),
                              (response.replace('reserve-copper', 'reserve-large'), 'operation'),
                              (response.replace('|1|70', '|2|41'), 'revision-conflict')]:
            guest.send('sc-m3-recv '+frame, kind='negative-input')
            guest.wait('SC_M3_REJECT reason='+reason)
            guest_state(guest, 40, 2, 'serpulo:170')
        exchange_after = world(host)
        assert exchange_after['copper'] == '40'
        assert exchange_after['sector'] == exchange_before['sector']
        assert float(exchange_after['tick']) > float(exchange_before['tick'])
        assert int(exchange_after['updateId']) > int(exchange_before['updateId'])
        assert host.log.read_text().count('SC_M3_DEBIT ') == 2
        if args.two_sectors:
            guest_after = world(guest, 'frozenForest')
            assert guest_after['sector'] == guest_before['sector']
            assert float(guest_after['tick']) > float(guest_before['tick'])
            assert int(guest_after['updateId']) > int(guest_before['updateId'])
            assert guest_after['copper'] == guest_before['copper'] == '0'
            record['two_sectors'] = dict(before=guest_before, after=guest_after, guest_seeded=False)
        record['f3'] = dict(before=exchange_before, after=exchange_after, guest_authority='cache-only',
                            retry_no_double_debit=True, stale_engine_reply_rejected=True, guest_negative_checks=5)
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
