import concurrent.futures, csv, datetime, json, pathlib, subprocess, tempfile, os
root=pathlib.Path(__file__).resolve().parents[1]
catalog=root/'assets/ilradio_k3.koplugin/streams.tsv'
rows=list(csv.reader(catalog.read_text().splitlines(), delimiter='\t'))[1:]
rows.append(['Radio ZET','https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_ZETAAC.aac','','','Zewnętrzny operator StreamTheWorld · powiązanie niepotwierdzone','-'])
rows=list({(row[0],row[1]):row for row in rows}.values())
def check(pair):
    i,row=pair
    with tempfile.TemporaryDirectory() as d:
        p=pathlib.Path(d)
        r=subprocess.run(['curl','-fLsS','--max-redirs','5','--connect-timeout','4','--max-time','9','--max-filesize','65536','-D',str(p/'headers'),'-o',str(p/'audio'),'-w','%{http_code}\n%{url_effective}\n',row[1]],capture_output=True,text=True)
        meta=r.stdout.splitlines()
        entry={'station':row[0],'url':row[1],'curl_exit':r.returncode,'http':meta[0] if meta else '', 'final':meta[1] if len(meta)>1 else '', 'error':r.stderr[-500:]}
        if (p/'audio').exists() and (p/'audio').stat().st_size:
            probe=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,profile,bit_rate,sample_rate,channels','-of','json',str(p/'audio')],capture_output=True,text=True,timeout=8)
            try: stream=json.loads(probe.stdout)['streams'][0]
            except (KeyError,IndexError,ValueError): stream={}
            entry['probe']=stream
            if stream.get('codec_name') == 'aac':
                dec=subprocess.run([os.environ['FDK_DECODER'],str(p/'audio'),str(p/'decoded.wav')],capture_output=True,text=True,timeout=8)
                entry['decoder_facts']=dec.stderr[-2000:]
                import re
                flags=[int(x) for x in re.findall(r'flags=(\d+)',dec.stderr)]
                if any(x & 0x20000 for x in flags): stream['profile']='HE-AACv2'
                elif any(x & 0x8000 for x in flags): stream['profile']='HE-AAC' 
            headers=(p/'headers').read_text(errors='replace')
            entry['headers']=headers[-6000:]
            if stream.get('codec_name') in ('aac','mp3'):
                codec={'LC':'AAC-LC','HE-AAC':'HE-AAC','HE-AACv2':'HE-AAC v2'}.get(stream.get('profile'),'AAC') if stream['codec_name']=='aac' else 'MP3'
                rate=float(stream.get('bit_rate',0))/1000
                nominal=None
                for line in headers.splitlines():
                    if line.lower().startswith(('icy-br:','ice-bitrate:')):
                        try: nominal=int(line.split(':',1)[1].strip())
                        except ValueError: pass
                # Preserve measured sample rate, use server nominal only when consistent.
                kbps=nominal if nominal and abs(rate-nominal)<max(2,nominal*.04) else round(rate)
                row=[row[0],row[1],codec,str(kbps),row[4],datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),entry['final'],'ADTS' if codec!='MP3' else 'MPEG Audio',f'{rate:.2f}']
                entry['verified']=True
        if not entry.get('verified'):
            # Keep addresses as candidates, but remove stale success claims.
            row=[row[0],row[1],'','',row[4],'-','','','']
        return i,row,entry
if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results=sorted(pool.map(check,enumerate(rows)))
    catalog.write_text('# Stacja\tURL\tKodek próbki\tNominalny kb/s\tDostawca\tData weryfikacji\tAdres końcowy (próbka)\tFormat\tZmierzony kb/s\n'+''.join('\t'.join(row)+'\n' for _,row,_ in results))
    (root/'reports/catalog-evidence.json').write_text(json.dumps([e for _,_,e in results],ensure_ascii=False,indent=2))
    print('Verified',sum(bool(e.get('verified')) for _,_,e in results),'of',len(results))
