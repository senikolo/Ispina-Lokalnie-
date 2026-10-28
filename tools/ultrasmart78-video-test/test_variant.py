import json
import tempfile
import types
import unittest
import zipfile
from pathlib import Path

import make_test_variant as build
import sign_test_apk as signing

SOURCE = build.HERE.parents[1] / "downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk"


class SeparateTestAppTests(unittest.TestCase):
    def test_isolated_identity_and_preserved_application_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = Path(tmp) / "test.apk"
            build.make_test_variant(SOURCE, result)
            with zipfile.ZipFile(SOURCE) as source, zipfile.ZipFile(result) as target:
                self.assertIsNone(target.testzip())
                changed = {n for n in target.namelist() if target.read(n) != source.read(n)}
                self.assertEqual(changed, {"AndroidManifest.xml", "resources.arsc", "assets/main_ui.js", "assets/il-build.json"})
                # Resource IDs and all table content stay identical except the
                # package's fixed-width name, which has the same byte length.
                self.assertEqual(target.read("resources.arsc"), source.read("resources.arsc").replace(
                    build.base.PACKAGE.encode("utf-16le"), build.PACKAGE.encode("utf-16le")))
                meta = json.loads(target.read("assets/il-build.json"))
                self.assertEqual((meta["packageName"], meta["versionName"], meta["versionCode"], meta["label"]),
                                 (build.PACKAGE, build.VERSION, 20349, build.LABEL))
                manifest = target.read("AndroidManifest.xml")
                pos = 8
                import struct
                while struct.unpack_from("<H", manifest, pos)[0] != 1:
                    pos += struct.unpack_from("<I", manifest, pos + 4)[0]
                values, _ = build.base.string_pool(manifest, pos)
                self.assertIn(build.PACKAGE, values)
                self.assertIn(build.LABEL, values)
                self.assertIn(build.VERSION, values)
                self.assertIn(build.PACKAGE + ".androidx-startup", values)
                self.assertIn(build.PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", values)
                self.assertFalse(any(build.base.PACKAGE in s for s in values))
                self.assertIn("com.ispina.lokalnie.HomeActivity", values)
                js = target.read("assets/main_ui.js").decode()
                self.assertNotIn(build.base.PACKAGE, js)
                self.assertIn("window.IL_TEST_BUILD=true", js)
                for video in ("ZG7I26z3nZs", "uH6bZTGhFdE", "uY9qCLdLzuY"):
                    self.assertIn("/embed/" + video, js)

    def test_test_signer_refuses_production_apk(self):
        args = types.SimpleNamespace(input=str(SOURCE))
        with self.assertRaisesRegex(ValueError, "separate TEST"):
            signing.sign(args)
        self.assertNotEqual(json.loads((build.HERE / "test-certificate.json").read_text())["sha256"], signing.ORIGINAL_CERT)


if __name__ == "__main__":
    unittest.main()
