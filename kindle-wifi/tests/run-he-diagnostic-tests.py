"""Host integration tests; FFmpeg replaces the Kindle player, never a hardware test."""
import os, pathlib, subprocess, tempfile, time
plugin=pathlib.Path(__file__).resolve().parents[1]/'assets/ilradio_k3.koplugin'
with tempfile.TemporaryDirectory() as directory:
    root=pathlib.Path(directory); (root/'bin').mkdir()
    exe=root/'bin/mplayer'
    exe.write_text('''#!/usr/bin/env python3
import os,subprocess,sys
a=sys.argv[1:]; output=a[a.index('-ao')+1].split('file=',1)[1]
cmd=['ffmpeg','-v','error','-i',a[-1],'-c:a','pcm_s16le']
if os.environ.get('CORE_ONLY'): cmd+=['-ar','22050']
sys.exit(subprocess.call(cmd+['-f','wav','-y',output]))
''')
    exe.chmod(0o755)
    env=dict(os.environ,TMPDIR=directory,PATH=str(root/'bin')+':'+os.environ['PATH'])
    def call(*args): return subprocess.check_output(['sh',str(plugin/'player.sh'),*args],env=env,text=True).strip()
    def test(sample,expected):
        assert call('decode-test','mplayer',sample)=='testing'
        for _ in range(150):
            result=call('decode-result')
            if result: break
            time.sleep(.1)
        assert result.startswith(expected),(sample,result,expected)
    for sample in ['test-lc48.aac','test-he32.aac','test-hev224.aac','test-lc48.m4a','test-mp364.mp3','test-real-he32.aac','test-real-hev224.aac']:
        test(sample,'pcm:')
    env['CORE_ONLY']='1'
    test('test-real-he32.aac','unconfirmed')
    test('test-real-hev224.aac','unconfirmed')
    assert not (root/'ilradio-k3/diagnostic.pcm').exists()
print('PASS seven PCM diagnostics, HE core-only rejection and temporary PCM cleanup (host substitutes)')
