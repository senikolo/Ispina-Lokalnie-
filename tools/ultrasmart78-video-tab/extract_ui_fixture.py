"""Extract both the actual native HTML/CSP and the UI script for browser tests."""
import sys
import zipfile
from pathlib import Path

from patch_ultrasmart78_video import native_shell

with zipfile.ZipFile(sys.argv[1]) as apk:
    prefix = Path(sys.argv[2])
    prefix.with_suffix('.js').write_bytes(apk.read('assets/main_ui.js'))
    _, _, shell = native_shell(apk.read('classes4.dex'))
    prefix.with_suffix('.html').write_bytes(shell + b'"";</script></body></html>')
