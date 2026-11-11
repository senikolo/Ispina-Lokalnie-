#!/usr/bin/env python3
"""Build UltraSmart 79 with one YouTube player and the channel uploads list."""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import zipfile
import zlib
from pathlib import Path


OLD_NAME = "2.0.0-smart.77"
NEW_NAME = "2.0.0-smart.79"
OLD_CODE = 20348
NEW_CODE = 20350
PACKAGE = "com.ispina.mldcheck.smartai44demo"
MARKER = "__IL_ULTRASMART_YOUTUBE_CHANNEL_79__"
SOURCE_SHA256 = "7c49ebf0c20563335148952f6c6e6da27028bd974a700216cb2d711028184a23"
VIDEO_FRAME_POLICY = "frame-src https://www.youtube-nocookie.com; object-src 'none'"


def native_shell(data: bytes) -> tuple[int, int, bytes]:
    """Read the real HTML shell constant from HomeActivity's DEX string table."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Nieprawidłowy plik DEX ekranu aplikacji")
    count, table = struct.unpack_from("<II", data, 56)
    found = []
    for i in range(count):
        offset, = struct.unpack_from("<I", data, table + 4 * i)
        _, start = uleb(data, offset)
        end = data.index(b"\0", start)
        value = data[start:end]
        if value.startswith(b"<!doctype html>") and b"Content-Security-Policy" in value:
            found.append((start, end, value))
    if len(found) != 1:
        raise ValueError("Nie znaleziono jednoznacznego lokalnego ekranu aplikacji")
    return found[0]


def patch_shell_csp(data: bytes) -> bytes:
    start, end, shell = native_shell(data)
    if shell.count(b"frame-src 'none'; object-src 'none'") != 1:
        raise ValueError("Nieoczekiwana polityka CSP w UltraSmart 77")
    # Retain the string's byte/UTF-16 length and every DEX offset. The web page
    # title is restored by main_ui.js; its unused markup provides space for the
    # exact YouTube allowlist, without allowing arbitrary HTTPS frames.
    changed = shell.replace(b"frame-src 'none'; object-src 'none'", VIDEO_FRAME_POLICY.encode())
    title = b"<title>Ispina Lokalnie</title>"
    if changed.count(title) != 1:
        raise ValueError("Nieoczekiwany nagłówek lokalnego ekranu aplikacji")
    changed = changed.replace(title, b"")
    padding = len(shell) - len(changed)
    if padding < 0:
        raise ValueError("Brak miejsca na dokładną listę odtwarzaczy CSP")
    changed = changed.replace(b"</head>", b" " * padding + b"</head>")
    if len(changed) != end - start:
        raise ValueError("Zmiana CSP naruszyłaby układ pliku DEX")
    result = bytearray(data)
    result[start:end] = changed
    result[12:32] = hashlib.sha1(result[32:]).digest()
    struct.pack_into("<I", result, 8, zlib.adler32(result[12:]) & 0xffffffff)
    return bytes(result)

JS_PATCH = (Path(__file__).with_name("youtube_video.js")).read_text(encoding="utf-8")


def uleb(data: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7


def utf16_len(data: bytes, pos: int) -> tuple[int, int]:
    value = struct.unpack_from("<H", data, pos)[0]
    pos += 2
    if value & 0x8000:
        value = ((value & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
        pos += 2
    return value, pos


def string_pool(data: bytes, off: int) -> tuple[list[str], list[tuple[int, int, str]]]:
    count, = struct.unpack_from("<I", data, off + 8)
    flags, = struct.unpack_from("<I", data, off + 16)
    strings_start, = struct.unpack_from("<I", data, off + 20)
    utf8 = bool(flags & 0x100)
    values: list[str] = []
    payloads: list[tuple[int, int, str]] = []
    for i in range(count):
        rel, = struct.unpack_from("<I", data, off + 28 + i * 4)
        pos = off + strings_start + rel
        if utf8:
            _, pos = uleb(data, pos)
            byte_len, pos = uleb(data, pos)
            raw = data[pos : pos + byte_len]
            value = raw.decode("utf-8")
        else:
            char_len, pos = utf16_len(data, pos)
            byte_len = char_len * 2
            raw = data[pos : pos + byte_len]
            value = raw.decode("utf-16le")
        payloads.append((pos, byte_len, value))
        values.append(value)
    return values, payloads


def patch_manifest(data: bytes) -> bytes:
    buf = bytearray(data)
    pos = 8
    strings: list[str] | None = None
    payloads: list[tuple[int, int, str]] = []
    while pos + 8 <= len(buf):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", buf, pos)
        if chunk_size < header_size or pos + chunk_size > len(buf):
            raise ValueError("Nieprawidłowy rozmiar AndroidManifest.xml")
        if chunk_type == 0x0001:
            strings, payloads = string_pool(buf, pos)
            break
        pos += chunk_size
    if strings is None:
        raise ValueError("Brak puli tekstów w AndroidManifest.xml")

    code_offset = None
    name_index = None
    package_seen = False
    pos = 8
    while pos + 8 <= len(buf):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", buf, pos)
        if chunk_size < header_size or pos + chunk_size > len(buf):
            raise ValueError("Nieprawidłowy rozmiar AndroidManifest.xml")
        if chunk_type == 0x0102:
            ext = pos + header_size
            tag_index, = struct.unpack_from("<I", buf, ext + 4)
            if tag_index < len(strings) and strings[tag_index] == "manifest":
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", buf, ext + 8)
                attrs = ext + attr_start
                for i in range(attr_count):
                    at = attrs + i * attr_size
                    ns_index, attr_index = struct.unpack_from("<II", buf, at)
                    raw_index, = struct.unpack_from("<I", buf, at + 8)
                    if attr_index >= len(strings):
                        continue
                    attr_name = strings[attr_index]
                    ns = strings[ns_index] if ns_index < len(strings) else ""
                    value_type = buf[at + 15]
                    value_data, = struct.unpack_from("<I", buf, at + 16)
                    if attr_name == "package" and not ns:
                        package = strings[raw_index] if raw_index < len(strings) else ""
                        if package:
                            package_seen = True
                            if package != PACKAGE:
                                raise ValueError(f"Nieoczekiwany pakiet APK: {package}")
                    if ns != "http://schemas.android.com/apk/res/android":
                        continue
                    if attr_name == "versionCode":
                        if value_type not in (0x10, 0x11):
                            raise ValueError("Nieznany typ android:versionCode")
                        if value_data != OLD_CODE:
                            raise ValueError(f"Oczekiwano versionCode {OLD_CODE}, jest {value_data}")
                        code_offset = at + 16
                    elif attr_name == "versionName":
                        if value_type != 0x03 or value_data >= len(strings):
                            raise ValueError("Nieznany typ android:versionName")
                        name_index = value_data
        pos += chunk_size
    if not package_seen:
        raise ValueError("Nie znaleziono pakietu Androida w manifeście")
    if code_offset is None or name_index is None:
        raise ValueError("Brak android:versionCode lub android:versionName")
    if strings[name_index] != OLD_NAME:
        raise ValueError(f"Oczekiwano versionName {OLD_NAME}, jest {strings[name_index]!r}")
    start, length, value = payloads[name_index]
    if value != OLD_NAME:
        raise ValueError("Nie udało się wskazać tekstu android:versionName")
    encoded = NEW_NAME.encode("utf-8") if length == len(OLD_NAME.encode("utf-8")) else NEW_NAME.encode("utf-16le")
    if len(encoded) != length:
        raise ValueError("Nowa nazwa wersji ma inną długość niż stara")
    buf[start : start + length] = encoded
    struct.pack_into("<I", buf, code_offset, NEW_CODE)
    return bytes(buf)


def signature_entry(name: str) -> bool:
    upper = name.upper()
    if upper == "META-INF/MANIFEST.MF":
        return True
    if not upper.startswith("META-INF/"):
        return False
    leaf = upper.rsplit("/", 1)[-1]
    return leaf.endswith((".RSA", ".DSA", ".EC", ".SF", ".MF")) or leaf.startswith("SIG-")


def patch_apk(source: Path, output: Path) -> None:
    if source.resolve() == output.resolve():
        raise ValueError("Plik wynikowy nie może nadpisać UltraSmart 77")
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("APK nie jest zatwierdzonym oryginałem UltraSmart 77 (SHA-256)")
    with zipfile.ZipFile(source, "r") as zin:
        names = set(zin.namelist())
        for required in ("AndroidManifest.xml", "assets/main_ui.js", "classes4.dex"):
            if required not in names:
                raise ValueError(f"Brak wymaganego pliku APK: {required}")
        manifest = patch_manifest(zin.read("AndroidManifest.xml"))
        js = zin.read("assets/main_ui.js").decode("utf-8")
        if PACKAGE not in js or OLD_NAME not in js or str(OLD_CODE) not in js:
            raise ValueError("main_ui.js nie odpowiada wersji UltraSmart 77")
        if MARKER in js:
            raise ValueError("Zakładka Wideo jest już w tym APK")
        js = "document.title='Ispina Lokalnie';\n" + js.replace(OLD_NAME, NEW_NAME).replace(str(OLD_CODE), str(NEW_CODE))
        js = js + "\n;/* " + MARKER + " */\n" + JS_PATCH

        replacements: dict[str, bytes] = {
            "AndroidManifest.xml": manifest,
            "assets/main_ui.js": js.encode("utf-8"),
            "classes4.dex": patch_shell_csp(zin.read("classes4.dex")),
        }
        if "assets/il-build.json" in names:
            metadata = json.loads(zin.read("assets/il-build.json"))
            if metadata.get("versionName") != OLD_NAME or metadata.get("versionCode") != OLD_CODE:
                raise ValueError("il-build.json nie odpowiada wersji UltraSmart 77")
            metadata["versionName"] = NEW_NAME
            metadata["versionCode"] = NEW_CODE
            metadata["variant"] = "ULTRASMART-79"
            replacements["assets/il-build.json"] = json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8")

        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                if signature_entry(info.filename):
                    continue
                zout.writestr(info, replacements.get(info.filename, zin.read(info.filename)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Użycie: patch_ultrasmart78_video.py input.apk output.apk")
    patch_apk(Path(sys.argv[1]), Path(sys.argv[2]))
    print(f"Utworzono {NEW_NAME} z zakładką Wideo.")
