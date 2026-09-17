"""Build only production code and metadata; offline Java 17."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from resources import run

ROOT = Path(__file__).resolve().parents[1]
ENGINE = Path('/opt/mindustry/server-release.jar')
BUILD = ROOT / 'build'
CLASSES = BUILD / 'classes'
JAR = BUILD / 'shared-campaign-pc.jar'


def main():
    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    CLASSES.mkdir(parents=True)
    run(['nice', '-n', '10', 'javac', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
         '--release', '17', '-cp', str(ENGINE), '-d', str(CLASSES),
         str(ROOT / 'src/sc/SharedCampaignMod.java'), str(ROOT / 'src/sc/StdioLedger.java'), str(ROOT / 'src/sc/CampaignInventory.java'), str(ROOT / 'src/sc/SectorStore.java'), str(ROOT / 'src/sc/SectorSessions.java')])
    run(['nice', '-n', '10', 'jar', '-J-Xmx96m', '-J-XX:ActiveProcessorCount=1',
         '--create', '--no-manifest', '--file', str(JAR),
         '-C', str(CLASSES), 'sc', '-C', str(ROOT), 'mod.json'])
    result = {'exit': 0, 'jar_sha256': hashlib.sha256(JAR.read_bytes()).hexdigest(),
              'engine_sha256': hashlib.sha256(ENGINE.read_bytes()).hexdigest()}
    (BUILD / 'build-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
