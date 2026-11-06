"""Add Radio Kraków to the exact published UltraSmart 82 test build."""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from zipfile import ZipFile

BASE_SHA = "c69113c7085014d4dcf17d5fec08fbc045f68f9c59cd0e46d6de4ce2ba001ac8"
BASE_PACKAGE = "com.ispina.mldcheck.smartai78test"
ISOLATED_PACKAGE = "com.ispina.mldcheck.smartai82test"
BASE_LABEL = "Ispina Lokalnie YouTube TEST"
RADIO_LABEL = "Ispina Lokalnie 82Radio TEST"
RADIO_URL = "https://stream3.nadaje.com:9117/radiokrakow"
RADIO_NAME = "Radio Kraków"
RADIO_DESCRIPTION = "Regionalna rozgłośnia Małopolski — na żywo"
BASE_VERSION = 20355
NEW_VERSION = 20356


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Unexpected number of occurrences for {old!r}")
    return text.replace(old, new, 1)


def patch_ui(ui, isolated):
    lines = ui.splitlines(keepends=True)

    def insert_before(prefix, line):
        indices = [i for i, original in enumerate(lines) if original.startswith(prefix)]
        if len(indices) != 1:
            raise ValueError(f"Unexpected number of lines starting with {prefix!r}")
        lines.insert(indices[0], line + "\n")

    insert_before("      rmf:{name:", f"      krakow:{{name:'{RADIO_NAME}',description:'{RADIO_DESCRIPTION}',url:'{RADIO_URL}',type:'audio/mpeg'}},")
    insert_before("   rmf:{name:", f"   krakow:{{name:'{RADIO_NAME}',url:'{RADIO_URL}',btn:'rtvKrakow',retry:'rtvKrakowRetry',status:'rtvStatusKrakow'}},")
    rmf_card, = [line for line in lines if line.startswith('<div class="rtv-card" data-station="rmf">')]
    card = (rmf_card.rstrip("\n").replace('data-station="rmf"', 'data-station="krakow"')
            .replace("Rmf", "Krakow").replace("RMF FM", RADIO_NAME)
            .replace("Największe hity, Fakty Radio Kraków i muzyka przez całą dobę", RADIO_DESCRIPTION))
    insert_before('<div class="rtv-card" data-station="rmf">', card)
    ui = "".join(lines)
    for identifier in ("rtvRmfRetry", "rtvRmf", "rtvStatusRmf"):
        key = f"rmf:'{identifier}'"
        ui = replace_once(ui, key, key + ",krakow:'" + identifier.replace("Rmf", "Krakow") + "'")
    rmf_button = "button('rmf').textContent=current==='rmf'&&!audio.paused?'⏸ Pauza':'▶ Słuchaj';"
    ui = replace_once(ui, rmf_button, rmf_button + rmf_button.replace("'rmf'", "'krakow'"))
    for suffix in ("", "Retry"):
        legacy = f"el('rtvRmf{suffix}').addEventListener('click',function(){{playStation('rmf')}});"
        native = f"   capture('rtvRmf{suffix}',function(){{play('rmf')}});"
        for handler in (legacy, native):
            ui = replace_once(ui, handler, handler + ("\n" if handler == native else "")
                              + handler.replace("Rmf", "Krakow").replace("'rmf'", "'krakow'"))
    for variable in ("current", "key"):
        anchor = f"{variable}==='rmf'?'Nie udało się połączyć z RMF FM.'"
        ui = replace_once(ui, anchor, f"{variable}==='krakow'?'Nie udało się połączyć z Radiem Kraków.':" + anchor)

    ui = ui.replace(str(BASE_VERSION), str(NEW_VERSION)).replace(BASE_LABEL, RADIO_LABEL)
    if isolated:
        ui = ui.replace(f'"packageName":"{BASE_PACKAGE}"', f'"packageName":"{ISOLATED_PACKAGE}"')
        ui = ui.replace(f"packageName==='{BASE_PACKAGE}'", f"packageName==='{ISOLATED_PACKAGE}'")
    return ui.replace("ULTRASMART-82-YOUTUBE-CAMERAS-TEST", "ULTRASMART-82-YOUTUBE-CAMERAS-RADIO-TEST")


def patch_manifest(manifest, isolated):
    result = bytearray(manifest)
    label = BASE_LABEL.encode("utf-16le")
    result[:] = replace_once(result, label, RADIO_LABEL.encode("utf-16le"))
    if isolated:
        old_package = BASE_PACKAGE.encode("utf-16le")
        if result.count(old_package) != 3:
            raise ValueError("Unexpected package references in manifest")
        result[:] = result.replace(old_package, ISOLATED_PACKAGE.encode("utf-16le"))

    at = 8
    while at < len(result):
        kind, header, size = struct.unpack_from("<HHI", result, at)
        if not header <= size <= len(result) - at or not size:
            raise ValueError("Invalid manifest chunk")
        if kind == 0x0001:
            _, _, flags, strings, _ = struct.unpack_from("<IIIII", result, at + 8)
            if flags & 0x100:
                raise ValueError("Expected a UTF-16 manifest string pool")
            pool, offsets = at + strings, at + header
        if kind == 0x0102:
            ext = at + header
            tag, = struct.unpack_from("<I", result, ext + 4)
            def lookup(index):
                offset, = struct.unpack_from("<I", result, offsets + 4 * index)
                start = pool + offset
                count, = struct.unpack_from("<H", result, start)
                return result[start + 2:start + 2 + 2 * count].decode("utf-16le")
            if lookup(tag) == "manifest":
                start, stride, count = struct.unpack_from("<HHH", result, ext + 8)
                for i in range(count):
                    pos = ext + start + stride * i
                    name, = struct.unpack_from("<I", result, pos + 4)
                    if lookup(name) == "versionCode":
                        old, = struct.unpack_from("<I", result, pos + 16)
                        if old != BASE_VERSION:
                            raise ValueError("Unexpected base version code")
                        struct.pack_into("<I", result, pos + 16, NEW_VERSION)
                        return bytes(result)
                break
        at += size
    raise ValueError("No versionCode found")


def build(source, output, isolated):
    if source.resolve() == output.resolve():
        raise ValueError("The original APK is read-only")
    if hashlib.sha256(source.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError("Base APK differs from the published 82 TEST V2")
    with ZipFile(source) as base:
        meta = json.loads(base.read("assets/il-build.json"))
        meta.update(versionCode=NEW_VERSION, label=RADIO_LABEL,
                    packageName=ISOLATED_PACKAGE if isolated else BASE_PACKAGE,
                    variant="ULTRASMART-82-YOUTUBE-CAMERAS-RADIO-TEST")
        updates = {
            "AndroidManifest.xml": patch_manifest(base.read("AndroidManifest.xml"), isolated),
            "assets/main_ui.js": patch_ui(base.read("assets/main_ui.js").decode(), isolated).encode(),
            "assets/il-build.json": (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode(),
        }
        if isolated:
            updates["resources.arsc"] = replace_once(base.read("resources.arsc"), BASE_PACKAGE.encode("utf-16le"),
                                                     ISOLATED_PACKAGE.encode("utf-16le"))
        with ZipFile(output, "w") as result:
            for info in base.infolist():
                name = info.filename
                if name.startswith("META-INF/") and (name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")) or name == "META-INF/MANIFEST.MF"):
                    continue
                result.writestr(info, updates[name] if name in updates else base.read(name))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--isolated", action="store_true")
    args = parser.parse_args()
    build(args.source, args.output, args.isolated)
