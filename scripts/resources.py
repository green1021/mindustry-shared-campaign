"""Local Linux resource checks; terminate only the child we started."""
import os
from pathlib import Path
import subprocess
import time


def check():
    mem = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    available = int(mem['MemAvailable'].split()[0])
    load5 = os.getloadavg()[1]
    if available < 200 * 1024:
        raise RuntimeError('RAM guard: available < 200 MiB')
    if load5 > 4:
        raise RuntimeError('Load guard: load5 > 4')
    return {'available_kib': available, 'load5': load5}


def stop(p):
    if p.poll() is None:
        p.terminate()
        try:
            p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait(timeout=2)


def run(cmd, timeout=25):
    print('resources:', check(), flush=True)
    print('command:', ' '.join(map(str, cmd)), flush=True)
    p = subprocess.Popen(cmd)
    try:
        deadline = time.monotonic() + timeout
        while p.poll() is None:
            check()
            if time.monotonic() >= deadline:
                raise TimeoutError('Child timeout')
            time.sleep(.2)
        if p.returncode:
            raise subprocess.CalledProcessError(p.returncode, cmd)
    finally:
        stop(p)
