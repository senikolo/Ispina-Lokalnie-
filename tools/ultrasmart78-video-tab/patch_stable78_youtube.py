#!/usr/bin/env python3
"""Prepare a test update of the exact published UltraSmart 78; never sign here."""
import hashlib
import json
import struct
import sys
import zipfile
import argparse
import os
from pathlib import Path

import patch_ultrasmart78_video as base

SOURCE_SHA256 = '58c3ee07a21e85afe250f29252bdb79d94c33b263a1bbf33a55bf3f959562443'
PACKAGE = 'com.ispina.mldcheck.smartai78test'
CERT_SHA256 = '3c7438761d2accca13da08defe4b62a814c3a3d2201c374f5c2e943115591d82'
VERSION = '2.0.0-smart.81'
CODE = 20354
LABEL = 'Ispina Lokalnie YouTube TEST'
OLD_MARKER = ';/* __IL_ULTRASMART_WIDEO_TAB_78__ */'


def manifest(data):
    old = base.OLD_NAME, base.OLD_CODE, base.PACKAGE, base.NEW_NAME, base.NEW_CODE
    try:
        base.OLD_NAME, base.OLD_CODE, base.PACKAGE = '2.0.0-smart.78', 20351, PACKAGE
        base.NEW_NAME, base.NEW_CODE = VERSION, CODE
        result = bytearray(base.patch_manifest(data))
    finally:
        base.OLD_NAME, base.OLD_CODE, base.PACKAGE, base.NEW_NAME, base.NEW_CODE = old
    pos = 8
    while struct.unpack_from('<H', result, pos)[0] != 1:
        pos += struct.unpack_from('<I', result, pos + 4)[0]
    _, payloads = base.string_pool(result, pos)
    found = 0
    for start, length, value in payloads:
        if value != 'Ispina Lokalnie Ultra Smart 78':
            continue
        encoding = 'utf-8' if length == len(value.encode()) else 'utf-16le'
        label = LABEL.ljust(len(value)).encode(encoding)
        if len(label) != length:
            raise ValueError('Unexpected label length')
        result[start:start + length] = label
        found += 1
    if found != 1:
        raise ValueError('Stable 78 label not uniquely identified')
    return bytes(result)


def patch_apk(source: Path, output: Path, native_tools=None):
    if source.resolve() == output.resolve():
        raise ValueError('Do not overwrite the original')
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError('Expected the exact published stable 78 APK (SHA-256)')
    with zipfile.ZipFile(source) as original:
        js = original.read('assets/main_ui.js').decode()
        if js.count(OLD_MARKER) != 1 or not js.rstrip().endswith(';window.IL_RELEASE_CHANNEL="stable";'):
            raise ValueError('Unexpected stable 78 video module')
        js = js.split(OLD_MARKER, 1)[0]
        js = js.replace('2.0.0-smart.78', VERSION).replace('20351', str(CODE))
        module = base.JS_PATCH.replace(base.PACKAGE, PACKAGE)
        js += '\n;/* ' + base.MARKER + ' */\n' + module
        js += '\n;window.IL_RELEASE_CHANNEL="test";\n'
        js += "(function(){var v=document.getElementById('ilWideoViewSmart78');if(v){var p=document.createElement('p');p.textContent='Wersja testowa YouTube. Wybierz film z listy, aby rozpocząć odtwarzanie.';p.style.cssText='padding:12px;background:#fff0d4;color:#684d13';v.insertBefore(p,v.firstChild);}})();\n"
        meta = json.loads(original.read('assets/il-build.json'))
        if (meta.get('packageName'), meta.get('versionName'), meta.get('versionCode')) != (PACKAGE, '2.0.0-smart.78', 20351):
            raise ValueError('Unexpected stable 78 metadata')
        meta.update(versionName=VERSION, versionCode=CODE, channel='test', variant='ULTRASMART-81-YOUTUBE-TEST', label=LABEL)
        from patch_native_video import patch_native_dex
        native_tools = native_tools or os.environ.get('IL_SMALI_TOOLS')
        if not native_tools:
            raise ValueError('Set IL_SMALI_TOOLS to the directory of hash-pinned smali tools')
        replacements = {'AndroidManifest.xml':manifest(original.read('AndroidManifest.xml')),
                        'classes3.dex':patch_native_dex(original.read('classes3.dex'), native_tools),
                        'assets/main_ui.js':js.encode(),
                        'assets/il-build.json':json.dumps(meta,ensure_ascii=False,indent=2).encode()}
        output.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(output,'w') as result:
            for info in original.infolist():
                if not base.signature_entry(info.filename):
                    result.writestr(info,replacements.get(info.filename,original.read(info.filename)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--native-tools', type=Path, default=os.environ.get('IL_SMALI_TOOLS'))
    parser.add_argument('--allow-unconfigured-test', action='store_true', help='CI / offline diagnostics only; do not deliver this result to the user')
    args = parser.parse_args()
    if not args.allow_unconfigured_test:
        from verify_youtube_service import verify_service
        try:
            verify_service()
        except ValueError as error:
            raise SystemExit('APK nie jest gotowy do przekazania: ' + str(error))
    patch_apk(args.source,args.output,args.native_tools)
    print('Prepared YouTube TEST update:',PACKAGE,VERSION,CODE)
