"""Download hash-pinned build tools; these jars are never included in the APK."""
import concurrent.futures
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

LOCK = Path(__file__).with_name('smali-tools.lock.json')


def verify_tools(directory):
    directory = Path(directory)
    for entry in json.loads(LOCK.read_text()):
        path = directory / entry['file']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Missing or changed build tool: ' + entry['file'])
    return directory


def prepare(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    def download(entry):
        target = directory / entry['file']
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == entry['sha256']:
            return
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + entry['path'], timeout=30) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError('Build tool checksum mismatch: ' + entry['file'])
        target.write_bytes(data)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        list(executor.map(download, json.loads(LOCK.read_text())))
    return verify_tools(directory)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: prepare_smali_tools.py destination-directory')
    print(prepare(sys.argv[1]))
