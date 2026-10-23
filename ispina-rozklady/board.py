#!/usr/bin/env python3
"""Checked schedules for the interactive board. Never copy Dom Ludowy to Most."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

import update as u

GMINNA_INDEX = 'https://www.drwinia.pl/mieszkaniec/rozklady-jazdy-busow-i-autobusow'


def municipal_download(url):
    if not url.startswith('https://www.drwinia.pl/'):
        raise ValueError('Niedozwolone źródło gminne')
    with urllib.request.urlopen(url, timeout=30) as response:
        if not response.url.startswith('https://www.drwinia.pl/'):
            raise ValueError('Nieoczekiwane przekierowanie źródła gminnego')
        raw = response.read(5_000_001)
    if len(raw) > 5_000_000: raise ValueError('Za duży plik gminny')
    return raw


def valid_reference(target, ref, versions, pdfs, line):
    candidates = sorted((d, url) for d, url in versions[line] if d <= target)
    if not candidates: raise ValueError('Brak PDF dla ' + line)
    effective, url = candidates[-1]
    spec = next((s for s in ref['specs'] if s['line'] == line and s['url'] == url and s['valid_from'] == str(effective)), None)
    if not spec or hashlib.sha256(pdfs[url]).hexdigest() != spec['sha256']:
        raise ValueError('Zmieniony lub nieznany PDF ' + line)
    return spec


def epoch(target, time):
    return int(datetime.fromisoformat(str(target) + 'T' + time + ':00').replace(tzinfo=u.TZ).timestamp())


def checked_mld(files, target, ref, versions, pdfs):
    checked = u.verify_day(files, target, ref, versions, pdfs)
    raw = u.raw_departures(files, target)
    # A7's published PDF differs by one minute from the operational GTFS.
    # Only a bijective, order-preserving match within 60 seconds is accepted;
    # times come from the independently checked PDF, GTFS keeps trip identity.
    rows = [dict(r, source='GTFS + PDF', pdf=checked['validation'][r['line']]['pdf'])
            for r in checked['departures'] if r['stop'] == 'most']
    spec = valid_reference(target, ref, versions, pdfs, 'A7')
    for direction in ('north', 'south'):
        source = next(r for r in spec['rows'] if r['stop'] == 'Ispina Most' and r['direction'] == direction)
        expected = sorted(source[u.day_type(target)])
        actual = sorted((r for r in raw if r['stop'] == 'most' and r['line'] == 'A7' and r['direction'] == direction), key=lambda r:r['time'])
        if len(expected) != len(actual) or len(set(expected)) != len(expected):
            raise ValueError('Nie można jednoznacznie przypisać kursów A7')
        for planned, row in zip(expected, actual):
            if abs(epoch(target, planned) - epoch(target, row['time'])) > 60:
                raise ValueError('A7: różnica większa niż sprawdzona minuta')
            rows.append(dict(row, gtfs_time=row['time'], time=planned, source='PDF przewoźnika', pdf=spec['url']))
    trips = {t['trip_id']: t for t in files['trips.txt']}
    times = {(t['trip_id'], t['stop_id']):t for t in files['stop_times.txt'] if t['stop_id'] in ('119','67')}
    for row in rows:
        tid = row['trip_id']; st = times[tid,row['stop_id']]
        row.update(stop_name='Ispina Most', scheduled=epoch(target,row['time']),
                   gtfs_scheduled=epoch(target,row.get('gtfs_time',row['time'])),
                   sequence=int(st['stop_sequence']), route_id=trips[tid]['route_id'], service_date=target.strftime('%Y%m%d'))
    return rows, checked['validation']


def municipal_rows(target, ref):
    if not ref['reviewed_at'] <= str(target) <= ref['review_valid_until']:
        raise ValueError('Wygasła kontrola rozkładu gminnego')
    kind = u.day_type(target)
    result=[]
    for group in ref['groups']:
        if group['day_type'] != kind: continue
        direction = 'north' if group['direction'] == 'KRAKOW' else 'south'
        for time in group['times']:
            result.append({'line':'GMINNA','time':time,'scheduled':epoch(target,time),'direction':direction,
                           'stop':'dom-ludowy','stop_name':'Ispina Dom Ludowy',
                           'destination':'Kraków Czyżyny Dworzec' if direction=='north' else 'Mikluszowice Park & Ride',
                           'source':'PDF Gminy Drwinia','pdf':ref['pdf']})
    return result


def main():
    p=argparse.ArgumentParser();p.add_argument('--offline-directory',type=Path);p.add_argument('--date');p.add_argument('--output',type=Path,default=u.ROOT/'out')
    args=p.parse_args();today=date.fromisoformat(args.date) if args.date else datetime.now(u.TZ).date()
    result={'schema':1,'generated_at':datetime.now(u.TZ).isoformat(),'timezone':'Europe/Warsaw','days':[],
            'gminna_notice':'Linia gminna: odjazdy z Domu Ludowego (Ispina III). Osobny odjazd z Mostu nie jest potwierdzony w oficjalnym PDF.'}
    code=0
    try:
        ref=json.loads((u.ROOT/'reference.json').read_text());gref=json.loads((u.ROOT/'gminna-reference.json').read_text())
        if args.offline_directory:
            raw=(args.offline_directory/'GTFS.zip').read_bytes();html=(args.offline_directory/'page.html').read_text()
            gpdf=(args.offline_directory/'gminna-official.pdf').read_bytes();gindex=(args.offline_directory/'gminna-index.html').read_text()
        else:
            raw=u.download(u.discover_feed(u.download(u.INDEX,2_000_000).decode()));html=u.download(u.PDF_INDEX,3_000_000).decode()
            gpdf=municipal_download(gref['pdf']);gindex=municipal_download(GMINNA_INDEX).decode()
        files=u.load_gtfs(raw);u.validate_stops(files)
        # Detect any additional MLD line serving Most rather than silently omitting it.
        route_names={r['route_id']:r['route_short_name'] for r in files['routes.txt']}
        trips={t['trip_id']:t for t in files['trips.txt']}
        serving={route_names[trips[t['trip_id']]['route_id']] for t in files['stop_times.txt'] if t['stop_id'] in ('119','67') and t.get('pickup_type','0')!='1'}
        if serving != set(u.ROUTES):raise ValueError('Zmienił się zestaw linii przez Most: '+str(sorted(serving)))
        versions=u.official_versions(html)
        urls={url for line in u.ROUTES for d,url in versions[line] if d<=today+timedelta(days=6)}
        def pdf(url):
            if args.offline_directory:
                s=next(s for s in ref['specs'] if s['url']==url);period='new' if s['valid_from']=='2026-10-25' else 'old'
                return url,(args.offline_directory/(s['line']+'-'+period+'.pdf')).read_bytes()
            return url,u.download(url,5_000_000)
        with ThreadPoolExecutor(max_workers=4) as pool:pdfs=dict(pool.map(pdf,urls))
        links=u.Links();links.feed(gindex)
        gminna_valid=(hashlib.sha256(gpdf).hexdigest()==gref['sha256'] and any(u.urllib.parse.urljoin(GMINNA_INDEX,url)==gref['pdf'] for url,label in links.links if 'Rozkład jazdy gminnej' in label))
        for i in range(7):
            target=today+timedelta(days=i);day={'date':str(target),'departures':[],'warnings':[]}
            try:
                rows,validation=checked_mld(files,target,ref,versions,pdfs);day['departures']+=rows
                for line in ('A4','A65'):
                    if not validation[line]['valid']:day['warnings'].append(line+': rozkład wymaga kontroli; godziny ukryte.')
            except Exception as e:day['warnings'].append('MLD: '+str(e))
            try:
                if not gminna_valid:raise ValueError('zmieniony PDF lub odnośnik gminny')
                day['departures']+=municipal_rows(target,gref)
            except Exception as e:day['warnings'].append('Gminna: '+str(e)+'; godziny ukryte.')
            day['departures'].sort(key=lambda r:(r['scheduled'],r['line']))
            result['days'].append(day)
        result['feed_version']=files['feed_info.txt'][0].get('feed_version');result['review_valid_until']=ref['review_valid_until']
        if not result['days'][0]['departures']:code=1
    except Exception as e:result['error']=str(e);code=1
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'tablica.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'board_days':len(result['days']),'today_departures':len(result['days'][0]['departures']) if result['days'] else 0,'error':result.get('error')},ensure_ascii=False))
    return code


if __name__=='__main__':sys.exit(main())
