"""Run with the published stable 78 APK as the sole argument; no keys required."""
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import patch_stable78_youtube as patch

SOURCE = Path(sys.argv[1]) if len(sys.argv) == 2 else None


class Stable78UpdateTests(unittest.TestCase):
    def test_preserves_current_package_and_native_application(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'test79.apk'
            patch.patch_apk(SOURCE, output)
            with zipfile.ZipFile(SOURCE) as old, zipfile.ZipFile(output) as new:
                self.assertIsNone(new.testzip())
                expected = {n for n in old.namelist() if not patch.base.signature_entry(n)}
                self.assertEqual(expected,set(new.namelist()))
                changed = {n for n in expected if old.read(n) != new.read(n)}
                self.assertEqual(changed,{'AndroidManifest.xml','assets/main_ui.js','assets/il-build.json'})
                meta = json.loads(new.read('assets/il-build.json'))
                self.assertEqual((meta['packageName'],meta['versionName'],meta['versionCode']),
                                 (patch.PACKAGE,patch.VERSION,20352))
                js = new.read('assets/main_ui.js').decode()
                self.assertNotIn(patch.OLD_MARKER,js)
                self.assertNotIn('startPlayers',js)
                self.assertEqual(js.count('new window.YT.Player('),1)
                self.assertIn('https://' + patch.PACKAGE + '/',js)
                self.assertIn('Automatyczna lista oczekuje na uruchomienie usługi.',js)
                script = Path(tmp) / 'main_ui.js'
                script.write_text(js)
                subprocess.run(['node','--check',str(script)],check=True)

    def test_rejects_changed_input_and_overwriting_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            changed = Path(tmp) / 'other.apk'
            changed.write_bytes(SOURCE.read_bytes() + b'changed')
            with self.assertRaisesRegex(ValueError,'SHA-256'):
                patch.patch_apk(changed,Path(tmp) / 'out.apk')
            with self.assertRaisesRegex(ValueError,'overwrite'):
                patch.patch_apk(SOURCE,SOURCE)


if __name__ == '__main__':
    if SOURCE is None:
        raise SystemExit('Usage: test_stable78_update.py published-stable78.apk')
    unittest.main(argv=[sys.argv[0]])
