"""Regression checks against the exact UltraSmart 77 APK, without signing keys."""
import importlib.util
import json
import hashlib
import struct
import zlib
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("patch_video", HERE / "patch_ultrasmart78_video.py")
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)
SOURCE = ROOT / "downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk"


class VideoPatchTests(unittest.TestCase):
    def test_exact_base_and_preserved_native_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "unsigned.apk"
            patch.patch_apk(SOURCE, output)
            with zipfile.ZipFile(SOURCE) as original, zipfile.ZipFile(output) as result:
                self.assertIsNone(result.testzip())
                expected = {n for n in original.namelist() if not patch.signature_entry(n)}
                self.assertEqual(set(result.namelist()), expected)
                changed = {n for n in expected if original.read(n) != result.read(n)}
                self.assertEqual(changed, {"AndroidManifest.xml", "assets/main_ui.js", "assets/il-build.json", "classes4.dex"})
                old_dex, new_dex = original.read('classes4.dex'), result.read('classes4.dex')
                start, end, old_shell = patch.native_shell(old_dex)
                _, _, new_shell = patch.native_shell(new_dex)
                self.assertIn(b"frame-src 'none'; object-src 'none'", old_shell)
                self.assertIn(patch.VIDEO_FRAME_POLICY.encode(), new_shell)
                self.assertEqual(len(new_dex), len(old_dex))
                self.assertEqual(old_dex[:8], new_dex[:8])
                self.assertEqual(old_dex[32:start], new_dex[32:start])
                self.assertEqual(old_dex[end:], new_dex[end:])
                self.assertEqual(new_dex[12:32], hashlib.sha1(new_dex[32:]).digest())
                self.assertEqual(struct.unpack_from('<I', new_dex, 8)[0], zlib.adler32(new_dex[12:]) & 0xffffffff)
                info = json.loads(result.read("assets/il-build.json"))
                self.assertEqual((info["packageName"], info["versionName"], info["versionCode"]),
                                 (patch.PACKAGE, "2.0.0-smart.78", 20349))
                # Reversing the manifest edit yields exactly the original binary.
                old_manifest = original.read("AndroidManifest.xml")
                patch.OLD_NAME, patch.NEW_NAME = patch.NEW_NAME, patch.OLD_NAME
                patch.OLD_CODE, patch.NEW_CODE = patch.NEW_CODE, patch.OLD_CODE
                try:
                    self.assertEqual(patch.patch_manifest(result.read("AndroidManifest.xml")), old_manifest)
                finally:
                    patch.OLD_NAME, patch.NEW_NAME = patch.NEW_NAME, patch.OLD_NAME
                    patch.OLD_CODE, patch.NEW_CODE = patch.NEW_CODE, patch.OLD_CODE
                script = Path(tmp) / "main_ui.js"
                script.write_bytes(result.read("assets/main_ui.js"))
                subprocess.run(["node", "--check", str(script)], check=True)
                # Run the actual appended source. A bare marker used to throw
                # ReferenceError before even entering the Wideo initializer.
                appended = script.read_text().split("\n;/* " + patch.MARKER, 1)[1]
                subprocess.run(["node", "-e", "global.document={getElementById:()=>null};\n/* " + patch.MARKER + appended], check=True)

    def test_rejects_changed_base_and_in_place_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            wrong = Path(tmp) / "other.apk"
            wrong.write_bytes(SOURCE.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                patch.patch_apk(wrong, Path(tmp) / "result.apk")
            with self.assertRaisesRegex(ValueError, "nadpisać"):
                patch.patch_apk(SOURCE, SOURCE)


if __name__ == "__main__":
    unittest.main()
