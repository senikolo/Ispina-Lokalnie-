#!/usr/bin/env python3
"""Build a separately installable test variant from the exact UltraSmart 77."""
from __future__ import annotations

import importlib.util
import json
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("video_patch", HERE.parent / "ultrasmart78-video-tab/patch_ultrasmart78_video.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

PACKAGE = "com.ispina.mldcheck.smartai78test"
VERSION = "2.0.0-smart.78-test.2"
VERSION_CODE = 20350
LABEL = "Ispina Lokalnie 78 TEST"
VARIANT = "ULTRASMART-78-WIDEO-TEST"


def rewrite_pool(data: bytes) -> bytes:
    """Keep string indices intact while changing package, version and label."""
    pos = 8
    while pos + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        if not header <= size <= len(data) - pos:
            raise ValueError("Invalid manifest chunk")
        if kind != 1:
            pos += size
            continue
        count, styles, flags = struct.unpack_from("<III", data, pos + 8)
        if header != 28 or styles:
            raise ValueError("Unexpected styled manifest string pool")
        values, _ = base.string_pool(data, pos)
        values = [s.replace(base.PACKAGE, PACKAGE) for s in values]
        values = [VERSION if s == base.NEW_NAME else s for s in values]
        labels = [i for i, s in enumerate(values) if s == "Ispina Lokalnie UltraSmart 4.4.2 NEWS"]
        if len(labels) != 1:
            raise ValueError("Unexpected UltraSmart 77 launcher label")
        values[labels[0]] = LABEL
        payload = bytearray()
        offsets = []
        for value in values:
            offsets.append(len(payload))
            utf16 = value.encode("utf-16le")
            units = len(utf16) // 2
            if flags & 0x100:
                raw = value.encode("utf-8")
                for length in (units, len(raw)):
                    if length >= 0x8000:
                        raise ValueError("Oversized manifest string")
                    payload.extend(bytes([0x80 | (length >> 8), length & 0xff]) if length > 0x7f else bytes([length]))
                payload.extend(raw + b"\0")
            else:
                if units >= 0x8000:
                    raise ValueError("Oversized manifest string")
                payload.extend(struct.pack("<H", units) + utf16 + b"\0\0")
        payload.extend(b"\0" * (-len(payload) % 4))
        start = 28 + 4 * count
        chunk = struct.pack("<HHIIIIII", 1, 28, start + len(payload), count, 0, flags, start, 0)
        chunk += struct.pack("<" + "I" * count, *offsets) + payload
        result = bytearray(data[:pos] + chunk + data[pos + size:])
        struct.pack_into("<I", result, 4, len(result))
        # This is an update of the already installed TEST package, not 77.
        at = 8
        while at + 8 <= len(result):
            kind, hdr, chunk_size = struct.unpack_from("<HHI", result, at)
            if kind == 0x0102:
                ext = at + hdr
                tag_index, = struct.unpack_from("<I", result, ext + 4)
                if values[tag_index] == 'manifest':
                    attr_start, attr_size, attr_count = struct.unpack_from("<HHH", result, ext + 8)
                    for index in range(attr_count):
                        attr = ext + attr_start + index * attr_size
                        attr_name, = struct.unpack_from("<I", result, attr + 4)
                        if values[attr_name] == 'versionCode':
                            original, = struct.unpack_from("<I", result, attr + 16)
                            if original != base.NEW_CODE:
                                raise ValueError('Unexpected input TEST versionCode')
                            struct.pack_into("<I", result, attr + 16, VERSION_CODE)
                            return bytes(result)
            at += chunk_size
        raise ValueError('No versionCode in manifest')
    raise ValueError("No manifest string pool")


def rewrite_resource_package(data: bytes) -> bytes:
    """Rename only the fixed-width resource package name; preserve IDs/data."""
    result = bytearray(data)
    kind, header, size = struct.unpack_from("<HHI", data)
    if kind != 2 or size != len(data):
        raise ValueError("Unexpected resource table")
    pos, changed = header, 0
    while pos + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        if not header <= size <= len(data) - pos:
            raise ValueError("Invalid resource table chunk")
        if kind == 0x0200:
            name = data[pos + 12:pos + 268].decode("utf-16le").rstrip("\0")
            if name != base.PACKAGE:
                raise ValueError("Unexpected resource package: " + name)
            result[pos + 12:pos + 268] = PACKAGE.encode("utf-16le").ljust(256, b"\0")
            changed += 1
        pos += size
    if changed != 1:
        raise ValueError("Expected exactly one resource package")
    return bytes(result)


TEST_BANNER = r"""
;/* ULTRASMART_78_SEPARATE_TEST_APP */
(function(){
 window.IL_TEST_BUILD=true;
 var d=document,s=d.querySelector('#iaBar .S');
 if(s)s.textContent='UltraSmart 78 TEST • osobna aplikacja';
 var v=d.getElementById('ilWideoViewSmart78');
 if(v){var note=d.createElement('p');note.textContent='Wersja testowa 78 — działa obok UltraSmart 77.';note.style.cssText='padding:10px;border-radius:10px;background:#fff0d4;color:#684d13;font-size:13px';v.insertBefore(note,v.firstChild);}
})();
"""


def make_test_variant(source: Path, output: Path) -> None:
    if source.resolve() == output.resolve():
        raise ValueError("Do not overwrite the original APK")
    with tempfile.TemporaryDirectory() as tmp:
        patched = Path(tmp) / "patched.apk"
        base.patch_apk(source, patched)
        with zipfile.ZipFile(patched) as src:
            js = src.read("assets/main_ui.js").decode("utf-8")
            js = js.replace(base.PACKAGE, PACKAGE).replace(base.NEW_NAME, VERSION)
            js = js.replace('"versionCode":' + str(base.NEW_CODE), '"versionCode":' + str(VERSION_CODE))
            js += TEST_BANNER
            metadata = json.loads(src.read("assets/il-build.json"))
            metadata.update(packageName=PACKAGE, versionName=VERSION, versionCode=VERSION_CODE, variant=VARIANT, label=LABEL)
            replacements = {
                "AndroidManifest.xml": rewrite_pool(src.read("AndroidManifest.xml")),
                "resources.arsc": rewrite_resource_package(src.read("resources.arsc")),
                "assets/main_ui.js": js.encode("utf-8"),
                "assets/il-build.json": json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8"),
            }
            output.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(output, "w") as dest:
                for item in src.infolist():
                    dest.writestr(item, replacements.get(item.filename, src.read(item.filename)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: make_test_variant.py original-ultrasmart77.apk unsigned-test.apk")
    make_test_variant(Path(sys.argv[1]), Path(sys.argv[2]))
    print(f"Built separate test application: {PACKAGE}, {VERSION}, versionCode {VERSION_CODE}")
