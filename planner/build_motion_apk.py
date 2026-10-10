"""Build the stable release from the tested R14.3 native base."""
from pathlib import Path
import argparse, hashlib, json, os, re, struct, subprocess, zipfile, zlib

ROOT=Path(__file__).resolve().parent
P=argparse.ArgumentParser()
P.add_argument('--base',type=Path,default=ROOT/'base-R14-3.apk')
P.add_argument('--signer',type=Path)
P.add_argument('--private-signing-config',type=Path)
args=P.parse_args()
UI=(ROOT/'assets/main_ui.js').read_bytes()
text=UI.decode()
meta=json.loads(text.split('var info=',1)[1].split(';try{var b=',1)[0])
OUT=ROOT/'output'
OUT.mkdir(exist_ok=True)
unsigned=OUT/'Ispina-Lokalnie-Live-1.83.19-unsigned.apk'
signed=OUT/'Ispina-Lokalnie-Live-1.83.19.apk'
with zipfile.ZipFile(args.base) as base_package:
 old_meta=json.loads(base_package.read('assets/il-build.json'))

def manifest_patch(data):
 b=bytearray(data)
 offset=8; strings=None
 while offset<len(b):
  kind,header,size=struct.unpack_from('<HHI',b,offset)
  assert size>=header and offset+size<=len(b)
  if kind==1:
   count,styles,flags,start,style_start=struct.unpack_from('<IIIII',b,offset+8)
   assert not styles and not style_start and not flags&256
   strings=[];changed=[]
   for i in range(count):
    at=offset+start+struct.unpack_from('<I',b,offset+header+i*4)[0]
    length=struct.unpack_from('<H',b,at)[0];assert length<0x8000
    value=b[at+2:at+2+length*2].decode('utf-16le')
    if value==old_meta['versionName']:value=meta['versionName'];changed.append('versionName')
    if value==old_meta['label']:value=meta['label'];changed.append('label')
    if value==old_meta['packageName']:value=meta['packageName'];changed.append('packageName')
    if value==old_meta['packageName']+'.androidx-startup':value=meta['packageName']+'.androidx-startup';changed.append('authorities')
    if value==old_meta['packageName']+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION':value=meta['packageName']+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION';changed.append('receiverPermission')
    strings.append(value)
   assert sorted(changed)==['authorities','label','packageName','receiverPermission','versionName']
   assert not any(old_meta['packageName'] in value for value in strings)
   payload=bytearray();indices=[]
   for value in strings:
    indices.append(len(payload));encoded=value.encode('utf-16le')
    payload+=struct.pack('<H',len(encoded)//2)+encoded+b'\0\0'
   payload+=b'\0'*((-len(payload))%4)
   new_start=header+count*4
   pool=bytearray(b[offset:offset+header])
   struct.pack_into('<I',pool,4,new_start+len(payload));struct.pack_into('<I',pool,20,new_start)
   pool+=struct.pack('<'+'I'*count,*indices)+payload
   b=b[:offset]+pool+b[offset+size:];struct.pack_into('<I',b,4,len(b))
   break
  offset+=size
 assert strings
 offset=8;changed=0;values={}
 while offset<len(b):
  kind,header,size=struct.unpack_from('<HHI',b,offset)
  if kind==0x102:
   ext=offset+16;start,width,count=struct.unpack_from('<HHH',b,ext+8)
   for i in range(count):
    at=ext+start+i*width;key=strings[struct.unpack_from('<I',b,at+4)[0]]
    typ=b[at+15];value=struct.unpack_from('<I',b,at+16)[0]
    if key=='versionCode':assert value==old_meta['versionCode'];struct.pack_into('<I',b,at+16,meta['versionCode']);changed+=1
    if key=='package':assert typ==3 and strings[value]==meta['packageName']
  offset+=size
 assert changed==1
 return bytes(b)

def aligned_info(item,stream):
 info=zipfile.ZipInfo(item.filename,item.date_time)
 info.compress_type=item.compress_type;info.external_attr=item.external_attr;info.create_system=item.create_system;info.comment=item.comment
 if info.compress_type==zipfile.ZIP_STORED:
  pad=(-(stream.fp.tell()+30+len(item.filename.encode('utf8'))))%4
  if pad:info.extra=struct.pack('<HH',0xd935,pad)+b'\0'*pad
 return info

with zipfile.ZipFile(args.base) as original,zipfile.ZipFile(unsigned,'w') as result:
 changed=[]
 for item in original.infolist():
  if item.filename.startswith('META-INF/') and (item.filename.endswith(('.SF','.RSA','.DSA','.EC')) or item.filename=='META-INF/MANIFEST.MF'):continue
  data=original.read(item)
  if item.filename=='AndroidManifest.xml':data=manifest_patch(data)
  elif item.filename=='assets/main_ui.js':data=UI
  elif item.filename=='assets/il-build.json':data=json.dumps(meta,ensure_ascii=False).encode()
  if data!=original.read(item):changed.append(item.filename)
  result.writestr(aligned_info(item,result),data)
 assert sorted(changed)==['AndroidManifest.xml','assets/il-build.json','assets/main_ui.js']

if args.signer and args.private_signing_config:
 config=json.loads(args.private_signing_config.read_text())
 environ=os.environ.copy();environ['IL_MOTION_SIGN_PASSWORD']=config['password']
 subprocess.run(['java','-jar',str(args.signer),'sign','--ks',config['keystore'],'--ks-key-alias',config['alias'],'--ks-pass','env:IL_MOTION_SIGN_PASSWORD','--key-pass','env:IL_MOTION_SIGN_PASSWORD','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--out',str(signed),str(unsigned)],env=environ,check=True)
 verification=subprocess.run(['java','-jar',str(args.signer),'verify','--verbose','--print-certs',str(signed)],check=True,capture_output=True,text=True)
 assert 'Signer #1 certificate SHA-256 digest: '+meta['signingCertificateSha256'] in verification.stdout
 (OUT/'signature-log.txt').write_text(verification.stdout)
 print(verification.stdout)
 with zipfile.ZipFile(signed) as final,zipfile.ZipFile(args.base) as original:
  assert final.testzip() is None
  assert final.read('classes.dex')==original.read('classes.dex')
  assert final.read('assets/main_ui.js')==UI
  assert json.loads(final.read('assets/il-build.json'))==meta
  for item in final.infolist():
   if item.compress_type==zipfile.ZIP_STORED:
    with signed.open('rb') as f:f.seek(item.header_offset);h=f.read(30)
    position=item.header_offset+30+struct.unpack_from('<H',h,26)[0]+struct.unpack_from('<H',h,28)[0]
    assert position%4==0,item.filename
 report={'apk':str(signed),'sha256':hashlib.sha256(signed.read_bytes()).hexdigest(),'crc':'PASS','zipAlignment':'PASS','sameDexAsR14':True,'metadata':meta,'changedPayloads':changed}
 (OUT/'apk-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(report,ensure_ascii=False))
else:
 print(json.dumps({'unsigned':str(unsigned),'metadata':meta},ensure_ascii=False))
