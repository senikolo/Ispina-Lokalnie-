import struct,json,hashlib,zipfile,zlib
from pathlib import Path
def decode_manifest(data):
    assert struct.unpack_from('<I', data, 4)[0] == len(data)
    strings = []
    elements = []
    offset = 8
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        assert size >= header and offset + size <= len(data)
        if kind == 1:
            count, styles, flags, start, _ = struct.unpack_from('<IIIII', data, offset + 8)
            assert not flags & 256
            for i in range(count):
                at = offset + start + struct.unpack_from('<I', data, offset + header + i * 4)[0]
                length = struct.unpack_from('<H', data, at)[0]
                assert length < 32768
                strings.append(data[at + 2:at + 2 + length * 2].decode('utf-16le'))
        elif kind == 258:
            ext = offset + 16
            name = struct.unpack_from('<I', data, ext + 4)[0]
            begin, width, count = struct.unpack_from('<HHH', data, ext + 8)
            attrs = {}
            for i in range(count):
                at = ext + begin + i * width
                key = struct.unpack_from('<I', data, at + 4)[0]
                typ = data[at + 15]
                value = struct.unpack_from('<I', data, at + 16)[0]
                attrs[strings[key]] = strings[value] if typ == 3 else value
            elements.append({'name': strings[name], 'attributes': attrs})
        offset += size
    assert offset == len(data)
    return elements

if __name__=='__main__':
    root=Path(__file__).resolve().parent
    final=root/'output/Ispina-Lokalnie-Live-1.83.19.apk'
    with zipfile.ZipFile(final) as package,zipfile.ZipFile(root/'base-R14-3.apk') as original:
        assert package.testzip() is None
        metadata=json.loads(package.read('assets/il-build.json'))
        elements=decode_manifest(package.read('AndroidManifest.xml'))
        attrs={e['name']:e['attributes'] for e in elements}
        manifest=attrs['manifest'];application=attrs['application'];sdk=attrs['uses-sdk']
        assert manifest['package']==metadata['packageName']=='com.ispina.mldcheck'
        assert manifest['versionName']==metadata['versionName']=='1.83.19'
        assert manifest['versionCode']==metadata['versionCode']==18319
        assert application['label']==metadata['label']=='Ispina - Lokalnie Live'
        assert metadata['channel']=='production' and metadata['variant']=='R15-FINAL'
        assert attrs['provider']['authorities']=='com.ispina.mldcheck.androidx-startup'
        assert not any('com.ispina.mldtest6' in str(e['attributes']) for e in elements)
        assert sdk['minSdkVersion']==23 and sdk['targetSdkVersion']==34
        assert package.read('assets/main_ui.js')==(root/'assets/main_ui.js').read_bytes()
        dex=package.read('classes.dex');assert dex==original.read('classes.dex')
        assert struct.unpack_from('<I',dex,8)[0]==zlib.adler32(dex[12:])&0xffffffff
        assert dex[12:32]==hashlib.sha1(dex[32:]).digest()
        assert struct.unpack_from('<I',dex,32)[0]==len(dex)
        changed=[name for name in original.namelist() if not name.startswith('META-INF/') and package.read(name)!=original.read(name)]
        assert sorted(changed)==['AndroidManifest.xml','assets/il-build.json','assets/main_ui.js']
        result={'sha256':hashlib.sha256(final.read_bytes()).hexdigest(),'size':final.stat().st_size,'manifest':{'package':manifest['package'],'versionName':manifest['versionName'],'versionCode':manifest['versionCode'],'label':application['label'],'minSdk':sdk['minSdkVersion'],'targetSdk':sdk['targetSdkVersion']},'crc':'PASS','dexIntegrity':'PASS','unchangedDex':'PASS','uiPayload':'MATCH','manifestMetadata':'PASS','changedPayloads':changed}
    (root/'output/manifest-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
