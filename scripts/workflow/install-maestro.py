"""Install the pinned official CLI locally, verifying GitHub's release digest."""
from pathlib import Path
import hashlib
import urllib.request
import zipfile

VERSION = '2.11.0'
SHA256 = '5384593cb4e7a106489e75a821d157dd43f4e438df6bc308b72e82c685e1283a'
ROOT = Path(__file__).resolve().parents[2]/'app/build/workflow-tools'
ROOT.mkdir(parents=True, exist_ok=True)
archive = ROOT/'maestro.zip'
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    size = archive.stat().st_size if archive.exists() else 0
    request = urllib.request.Request(
        f'https://github.com/mobile-dev-inc/Maestro/releases/download/cli-{VERSION}/maestro.zip',
        headers={'Range': f'bytes={size}-', 'User-Agent': 'Lianxu-local-verification'})
    with urllib.request.urlopen(request, timeout=60) as response:
        with archive.open('ab' if response.status == 206 else 'wb') as output:
            while chunk := response.read(1024*1024):
                output.write(chunk)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == SHA256, 'Archive hash mismatch'
with zipfile.ZipFile(archive) as source:
    source.extractall(ROOT)
print('Verified Maestro', VERSION, 'at', ROOT/'maestro/bin/maestro.bat')
