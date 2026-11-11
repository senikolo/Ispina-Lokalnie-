"""Preserve embedded YouTube navigation in WebView on Android 6 and newer."""
import hashlib
import re
import subprocess
import tempfile
from pathlib import Path

from prepare_smali_tools import verify_tools

SOURCE_DEX_SHA256 = 'eb31ff89d02cf416377fc869b349d46bc6d2d2b443c65dbb2d2b599775faec35'
TARGET = Path('com/ispina/lokalnie/HomeActivity$3.smali')
MODERN = 'shouldOverrideUrlLoading(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;)Z'
LEGACY = 'shouldOverrideUrlLoading(Landroid/webkit/WebView;Ljava/lang/String;)Z'


def methods(text):
    return {match.group(1):match.group(0) for match in re.finditer(r'^\.method [^\n]*?([^ \n]+\([^\n]+)\n.*?^\.end method',text,re.M|re.S)}


def instructions(method):
    return [line.strip() for line in method.splitlines() if line.strip() and not line.strip().startswith(('.', ':', '#'))]


def rewrite(text):
    found = methods(text)
    for name, guard in [
        (MODERN, '''    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->isForMainFrame()Z

    move-result v0

    if-nez v0, :il_main_frame

    const/4 v0, 0x0

    return v0

    :il_main_frame
'''),
        (LEGACY, '''    const-string v0, "https://www.youtube-nocookie.com/embed/"

    invoke-virtual {p2, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v0

    if-eqz v0, :il_other_url

    const/4 v0, 0x0

    return v0

    :il_other_url
''')]:
        original = found.get(name)
        if not original or original.count('    .registers 3\n') != 1:
            raise ValueError('Unexpected native WebView callback: ' + name)
        changed = original.replace('    .registers 3\n','    .registers 4\n\n' + guard,1)
        text = text.replace(original,changed,1)
    return text


def patch_native_dex(data, tools_directory):
    if hashlib.sha256(data).hexdigest() != SOURCE_DEX_SHA256:
        raise ValueError('Unexpected stable 78 WebView DEX (SHA-256)')
    tools = verify_tools(tools_directory)
    def run(main, *args):
        subprocess.run(['java','-cp',str(tools / '*'),main,*map(str,args)],check=True,capture_output=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        source, output = tmp/'source.dex',tmp/'output.dex'
        source.write_bytes(data)
        old, new = tmp/'old',tmp/'new'
        run('org.jf.baksmali.Main','disassemble','--api','23',source,'-o',old)
        originals = {p.relative_to(old):p.read_text() for p in old.rglob('*.smali')}
        if TARGET not in originals:
            raise ValueError('WebView client not found')
        (old/TARGET).write_text(rewrite(originals[TARGET]))
        run('org.jf.smali.Main','assemble','--api','23',old,'-o',output)
        run('org.jf.baksmali.Main','disassemble','--api','23',output,'-o',new)
        results = {p.relative_to(new):p.read_text() for p in new.rglob('*.smali')}
        if originals.keys() != results.keys():
            raise ValueError('Native class set changed')
        for name in originals:
            if name != TARGET and originals[name] != results[name]:
                raise ValueError('Unrelated native class changed: ' + str(name))
        before, after = methods(originals[TARGET]),methods(results[TARGET])
        if before.keys() != after.keys():
            raise ValueError('Native method set changed')
        for name in before:
            if name not in (MODERN,LEGACY) and before[name] != after[name]:
                raise ValueError('Unrelated WebView method changed')
        for name in (MODERN,LEGACY):
            previous_instructions = instructions(before[name])
            if instructions(after[name])[-len(previous_instructions):] != previous_instructions:
                raise ValueError('Original navigation behavior changed')
            if '    .registers 4' not in after[name] or '    return v0' not in after[name]:
                raise ValueError('WebView navigation guard missing')
        if '->isForMainFrame()Z' not in after[MODERN] or 'https://www.youtube-nocookie.com/embed/' not in after[LEGACY]:
            raise ValueError('Embedded player guard missing')
        return output.read_bytes()
