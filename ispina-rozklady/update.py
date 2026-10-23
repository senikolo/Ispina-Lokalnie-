#!/usr/bin/env python3
"""Publish only departures independently verified against reviewed official PDFs.

KMŁ has two different bus feeds. The commercial ald-gtfs.zip is obsolete;
discover the operational MLD GTFS.zip on the operator's official data index.
No WordPress credentials, plugins, or Android changes are involved.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import csv
from datetime import date, datetime, timedelta
import hashlib
from html.parser import HTMLParser
from io import BytesIO, StringIO
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request
import zipfile
from zoneinfo import ZoneInfo

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
TZ = ZoneInfo('Europe/Warsaw')
INDEX = 'https://gtfs.kolejemalopolskie.com.pl/'
PDF_INDEX = 'https://kolejemalopolskie.com.pl/pl/rozklad-jazdy/rozklady-autobusowe'
ROUTES = ('A4', 'A7', 'A65')
MAX_ZIP = 35_000_000
MAX_UNPACKED = 180_000_000
STOPS = {
    'wislisko': {'name': 'Ispina Wiślisko', 'source_name': 'Ispina Wiślisko', 'north': '4222', 'south': '4076', 'parent': '12515000000'},
    'dom-ludowy': {'name': 'Ispina Dom Ludowy', 'source_name': 'Ispina Dom Ludowy', 'north': '4533', 'south': '4352', 'parent': '12516000000'},
    'most': {'name': 'Ispina Most', 'source_name': 'Ispina', 'north': '119', 'south': '67', 'parent': '10216000000'},
}
BLUE, MID, INK, LIGHT, WHITE = '#174875', '#367cad', '#243f59', '#f3f7fc', '#ffffff'


class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.href = None; self.label = []; self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == 'a': self.href = dict(attrs).get('href'); self.label = []

    def handle_data(self, data):
        if self.href: self.label.append(data)

    def handle_endtag(self, tag):
        if tag == 'a' and self.href:
            self.links.append((self.href, ' '.join(' '.join(self.label).split())))
            self.href = None


def download(url, limit=MAX_ZIP):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or parsed.hostname not in {'gtfs.kolejemalopolskie.com.pl', 'kolejemalopolskie.com.pl', 'www.kolejemalopolskie.com.pl'}:
        raise ValueError('Niedozwolony adres źródła')
    req = urllib.request.Request(url, headers={'User-Agent': 'IspinaLokalnieTimetable/2.0 (+https://ispinalokalnie.com)'})
    with urllib.request.urlopen(req, timeout=40) as resp:
        final = urllib.parse.urlparse(resp.url)
        if final.scheme != 'https' or final.hostname != parsed.hostname:
            raise ValueError('Nieoczekiwane przekierowanie źródła')
        raw = resp.read(limit + 1)
    if len(raw) > limit: raise ValueError('Źródło przekroczyło limit rozmiaru')
    return raw


def discover_feed(html):
    p = Links(); p.feed(html)
    candidates = [urllib.parse.urljoin(INDEX, u) for u, name in p.links if name == 'GTFS.zip']
    if len(candidates) != 1: raise ValueError('Nie można jednoznacznie ustalić operacyjnego pliku MLD GTFS.zip')
    return candidates[0]


def load_gtfs(raw):
    if len(raw) > MAX_ZIP: raise ValueError('GTFS przekroczył limit rozmiaru')
    files = {}
    with zipfile.ZipFile(BytesIO(raw)) as z:
        if sum(i.file_size for i in z.infolist()) > MAX_UNPACKED: raise ValueError('GTFS przekroczył limit po rozpakowaniu')
        names = [Path(n).name.lower() for n in z.namelist()]
        if len(names) != len(set(names)): raise ValueError('Powtórzone pliki GTFS')
        for n in z.namelist():
            part = Path(n).name.lower()
            if part in {'stops.txt', 'routes.txt', 'trips.txt', 'stop_times.txt', 'calendar.txt', 'calendar_dates.txt', 'feed_info.txt', 'agency.txt'}:
                files[part] = list(csv.DictReader(StringIO(z.read(n).decode('utf-8-sig'))))
    for part in ('stops.txt', 'routes.txt', 'trips.txt', 'stop_times.txt', 'feed_info.txt'):
        if not files.get(part): raise ValueError('Brak wymaganego pliku: ' + part)
    if not files.get('calendar.txt') and not files.get('calendar_dates.txt'): raise ValueError('Brak kalendarza')
    for name, key in [('stops.txt', 'stop_id'), ('routes.txt', 'route_id'), ('trips.txt', 'trip_id')]:
        ids = [r[key] for r in files[name]]
        if len(ids) != len(set(ids)) or '' in ids: raise ValueError('Niejednoznaczne identyfikatory: ' + name)
    if any(a.get('agency_timezone') != 'Europe/Warsaw' for a in files.get('agency.txt', [])):
        raise ValueError('Nieobsługiwana strefa czasowa GTFS')
    return files


def active_services(files, target):
    day = target.strftime('%Y%m%d')
    dow = ('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')[target.weekday()]
    active = {c['service_id'] for c in files.get('calendar.txt', []) if c['start_date'] <= day <= c['end_date'] and c.get(dow) == '1'}
    for c in files.get('calendar_dates.txt', []):
        if c['date'] == day:
            if c['exception_type'] == '1': active.add(c['service_id'])
            elif c['exception_type'] == '2': active.discard(c['service_id'])
            else: raise ValueError('Niepoprawny wyjątek kalendarza')
    return active


def parse_time(raw):
    if not re.fullmatch(r'\d{1,2}:\d{2}:\d{2}', raw or ''): raise ValueError('Niepoprawna godzina GTFS')
    h, m, s = map(int, raw.split(':'))
    if h > 47 or m > 59 or s > 59 or s != 0: raise ValueError('Niepoprawna lub niepełnominutowa godzina GTFS')
    return h * 60 + m


def validate_stops(files):
    stops = {s['stop_id']: s for s in files['stops.txt']}
    for spec in STOPS.values():
        for direction in ('north', 'south'):
            s = stops.get(spec[direction], {})
            if s.get('stop_name') != spec['source_name'] or s.get('parent_station') != spec['parent'] or s.get('location_type', '0') != '0':
                raise ValueError('Zmieniła się identyfikacja przystanku ' + spec['name'])
            if not (50.113 < float(s['stop_lat']) < 50.119 and 20.382 < float(s['stop_lon']) < 20.398):
                raise ValueError('Niepoprawne współrzędne ' + spec['name'])
    return stops


def raw_departures(files, target):
    info = files['feed_info.txt'][0]
    if not info.get('feed_start_date', '') <= target.strftime('%Y%m%d') <= info.get('feed_end_date', ''):
        raise ValueError('GTFS nie obejmuje wybranego dnia')
    stops = validate_stops(files)
    routes = {r['route_id']: r['route_short_name'] for r in files['routes.txt'] if r.get('route_short_name') in ROUTES}
    if set(routes.values()) != set(ROUTES): raise ValueError('Brak jednej z wymaganych linii')
    platform = {s[d]: (slug, d) for slug, s in STOPS.items() for d in ('north', 'south')}
    all_trips = {t['trip_id']: t for t in files['trips.txt'] if t.get('route_id') in routes}
    times = defaultdict(list)
    for row in files['stop_times.txt']:
        if row['trip_id'] in all_trips: times[row['trip_id']].append(row)
    result = []
    # Include the previous service day so a valid 24:xx departure is never silently lost.
    for service_day, offset in [(target - timedelta(days=1), -1440), (target, 0)]:
        active = active_services(files, service_day)
        for tid, trip in all_trips.items():
            if trip['service_id'] not in active: continue
            rows = sorted(times[tid], key=lambda r: int(r['stop_sequence']))
            previous = -1
            for row in rows:
                minute = parse_time(row.get('departure_time') or row.get('arrival_time'))
                if minute < previous: raise ValueError('Godziny kursu nie są uporządkowane')
                previous = minute
                local = minute + offset
                if row['stop_id'] not in platform or not 0 <= local < 1440: continue
                if row.get('pickup_type', '0') == '1': continue
                slug, direction = platform[row['stop_id']]
                destination = stops[rows[-1]['stop_id']]['stop_name']
                result.append({'stop': slug, 'stop_id': row['stop_id'], 'line': routes[trip['route_id']], 'direction': direction,
                               'time': f'{local // 60:02d}:{local % 60:02d}', 'destination': destination, 'trip_id': tid})
    return sorted(result, key=lambda r: (r['time'], r['line'], r['destination']))


def official_versions(html):
    """Read dated PDFs from each exact bus section; refuse unknown layouts."""
    versions = {}
    for line in ROUTES:
        pattern = r'<strong>\s*' + line + r'\s*</strong>(.*?)(?=<div class="bus_name">|\Z)'
        sections = re.findall(pattern, html, re.S)
        if len(sections) != 1: raise ValueError('Zmienił się układ strony oficjalnych rozkładów')
        p = Links(); p.feed(sections[0]); versions[line] = []
        for href, text in p.links:
            if '.pdf' not in href.lower(): continue
            match = re.search(r'(\d{1,2})\.(\d{2})\.(\d{4})', text)
            if not match: raise ValueError('PDF bez daty obowiązywania dla ' + line)
            d, m, y = map(int, match.groups())
            versions[line].append((date(y, m, d), urllib.parse.urljoin(PDF_INDEX, href)))
        if not versions[line]: raise ValueError('Nie znaleziono PDF dla ' + line)
    return versions


def day_type(target):
    # The audited review expires in November 2026. Includes Polish public holidays.
    if target.isoformat() in {'2026-11-01', '2026-11-11'} or target.weekday() == 6: return 'sunday'
    return 'saturday' if target.weekday() == 5 else 'weekday'


def verify_day(files, target, references, versions, pdf_bytes):
    if target.isoformat() > references['review_valid_until'] or target.isoformat() < references['reviewed_at']:
        raise ValueError('Wymagana ponowna kontrola rozkładów przez redakcję')
    raw = raw_departures(files, target)
    published, validation = [], {}
    for line in ROUTES:
        current = sorted((d, u) for d, u in versions[line] if d <= target)
        if not current:
            validation[line] = {'valid': False, 'reason': 'Brak oficjalnego PDF na wybrany dzień'}; continue
        effective, url = current[-1]
        ref = next((s for s in references['specs'] if s['line'] == line and s['url'] == url and s['valid_from'] == effective.isoformat()), None)
        if not ref or hashlib.sha256(pdf_bytes[url]).hexdigest() != ref['sha256']:
            validation[line] = {'valid': False, 'reason': 'Nowy lub zmieniony PDF wymaga kontroli', 'pdf': url}; continue
        differences = []
        for slug, spec in STOPS.items():
            for direction in ('north', 'south'):
                row = next((r for r in ref['rows'] if r['stop'] == spec['name'] and r['direction'] == direction), None)
                expected = Counter(row[day_type(target)] if row else [])
                actual = Counter(r['time'] for r in raw if r['stop'] == slug and r['line'] == line and r['direction'] == direction)
                if actual != expected:
                    differences.append({'stop': slug, 'direction': direction, 'only_gtfs': list((actual - expected).elements()), 'only_pdf': list((expected - actual).elements())})
        valid = not differences
        validation[line] = {'valid': valid, 'pdf': url, 'pdf_sha256': ref['sha256'], 'differences': differences}
        if valid: published.extend(r for r in raw if r['line'] == line)
    return {'date': target.isoformat(), 'departures': sorted(published, key=lambda r: (r['time'], r['line'], r['destination'])), 'validation': validation}


def font(size, bold=False):
    path = '/usr/share/fonts/truetype/dejavu/DejaVuSans' + ('-Bold' if bold else '') + '.ttf'
    return ImageFont.truetype(path, size)


def draw_image(path, target, slug, day=None, error=None):
    width = 720
    groups = []
    if day:
        for line in ROUTES:
            rows = [r for r in day['departures'] if r['stop'] == slug and r['line'] == line]
            for direction in ('north', 'south'):
                selected = [r for r in rows if r['direction'] == direction]
                if selected:
                    dest = 'Nowe Brzesko / Proszowice' if direction == 'north' and line == 'A7' else 'Nowe Brzesko' if direction == 'north' else 'Podłęże' if line == 'A7' else 'Bochnia'
                    groups.append((line + ' → ' + dest, [r['time'] for r in selected]))
    blocked = [line for line in ROUTES if (line != 'A7' or slug == 'most') and (not day or not day['validation'][line]['valid'])]
    height = 260 + sum(65 + ((len(times) + 3) // 4) * 48 for _, times in groups) + (130 if blocked else 0) + (70 if error else 0)
    img = Image.new('RGB', (width, height), WHITE); d = ImageDraw.Draw(img)
    d.rounded_rectangle((2, 2, width - 3, height - 3), radius=20, outline='#dbe5ef', width=2)
    d.rounded_rectangle((3, 3, width - 4, 151), radius=20, fill=BLUE)
    d.rectangle((3, 125, width - 4, 151), fill=BLUE)
    d.text((26, 20), 'ISPINA LOKALNIE • ODJAZDY PLANOWE', font=font(18, True), fill=WHITE)
    d.text((26, 52), STOPS[slug]['name'], font=font(33, True), fill=WHITE)
    d.text((26, 103), target.strftime('%d.%m.%Y'), font=font(29, True), fill=WHITE)
    y = 170
    for heading, times in groups:
        d.text((28, y), heading, font=font(23, True), fill=BLUE); y += 43
        for i, value in enumerate(times):
            x = 28 + (i % 4) * 172
            if i and i % 4 == 0: y += 48
            d.rounded_rectangle((x, y, x + 152, y + 41), radius=8, fill=LIGHT)
            d.text((x + 23, y + 3), value, font=font(29, True), fill=INK)
        y += 70
    if blocked:
        d.rounded_rectangle((20, y, width - 20, y + 108), radius=12, fill=LIGHT)
        d.text((34, y + 12), ', '.join(blocked) + ' — sprawdź u przewoźnika', font=font(23, True), fill=BLUE)
        d.text((34, y + 49), 'Godziny ukryte: wymagają potwierdzenia.', font=font(21), fill=INK)
        d.text((34, y + 78), 'Odnośniki i pełna tabela pod obrazem.', font=font(20), fill=INK)
    d.text((27, height - 61), 'Sprawdź datę. Inna data niż dzień podróży?', font=font(20, True), fill=BLUE)
    d.text((27, height - 33), 'Otwórz szczegóły. Źródło: KMŁ. Bez opóźnień LIVE.', font=font(19), fill=INK)
    img.save(path, 'PNG', optimize=True)


def write_details(folder, metadata):
    lines = ['# Ispina Lokalnie — planowe odjazdy', '', '**Sprawdź datę podróży. Starszy dokument nie potwierdza dzisiejszych odjazdów.**', '',
             'Kontrola danych: ' + metadata['generated_at'], '', '[Oficjalne rozkłady i komunikaty KMŁ](' + PDF_INDEX + ')', '',
             '[KiedyPrzyjedzie: Nowe Brzesko / Proszowice](https://kolejemalopolskie.kiedyprzyjedzie.pl/#/stops/690378:741882/departures) · '
             '[KiedyPrzyjedzie: Bochnia / Podłęże](https://kolejemalopolskie.kiedyprzyjedzie.pl/#/stops/690378:741881/departures)', '',
             'Czasy są planowe, bez opóźnień na żywo. Linia gminna pozostaje na stronie Ispina Lokalnie.', '']
    if metadata.get('error'): lines += ['**Nie udało się potwierdzić aktualności danych. Godziny zostały ukryte.**', '']
    for day in metadata.get('days', []):
        lines += ['## ' + day['date'], '']
        for line, v in day['validation'].items():
            label = 'potwierdzona zgodność GTFS z PDF' if v['valid'] else 'godziny ukryte — sprawdź oficjalny rozkład'
            lines += [f'**{line}: {label}.** [PDF przewoźnika]({v.get("pdf", PDF_INDEX)})', '']
        for slug, spec in STOPS.items():
            lines += ['### ' + spec['name'], '', '| Godzina | Linia | Kierunek / przystanek końcowy |', '| --- | --- | --- |']
            for row in day['departures']:
                if row['stop'] == slug:
                    lines.append('| ' + row['time'] + ' | ' + row['line'] + ' | ' + row['destination'].replace('|', '/') + ' |')
            lines += ['']
    (folder / 'README.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', default=str(ROOT / 'out'))
    p.add_argument('--input-zip', help='Offline audit only; requires --offline-directory')
    p.add_argument('--offline-directory', type=Path)
    p.add_argument('--date', help='Audit date; never used by scheduled production workflow')
    args = p.parse_args()
    folder = Path(args.output); folder.mkdir(parents=True, exist_ok=True)
    today = date.fromisoformat(args.date) if args.date else datetime.now(TZ).date()
    metadata = {'generated_at': datetime.now(TZ).isoformat(), 'timezone': 'Europe/Warsaw', 'data_type': 'scheduled_not_realtime',
                'date': today.isoformat(), 'valid': False, 'status': 'unavailable', 'days': []}
    # Delete all prior outputs, including JSON/tables, before producing a new result.
    for stale in folder.iterdir():
        if stale.is_file(): stale.unlink()
    try:
        ref = json.loads((ROOT / 'reference.json').read_text())
        if args.input_zip:
            if not args.offline_directory: raise ValueError('Brak lokalnych źródeł PDF do audytu')
            raw = Path(args.input_zip).read_bytes(); metadata['source'] = 'offline_audit'
            html = (args.offline_directory / 'page.html').read_text()
        else:
            url = discover_feed(download(INDEX, 2_000_000).decode('utf-8'))
            raw = download(url); metadata['source'] = url
            html = download(PDF_INDEX, 3_000_000).decode('utf-8')
        files = load_gtfs(raw); metadata['feed_sha256'] = hashlib.sha256(raw).hexdigest()
        metadata['feed_info'] = files['feed_info.txt'][0]
        versions = official_versions(html)
        needed = {u for line in ROUTES for d, u in versions[line] if d <= today + timedelta(days=1)}
        def get_pdf(url):
            if args.offline_directory:
                s = next(s for s in ref['specs'] if s['url'] == url)
                period = 'new' if s['valid_from'] == '2026-10-25' else 'old'
                return url, (args.offline_directory / (s['line'] + '-' + period + '.pdf')).read_bytes()
            return url, download(url, 5_000_000)
        with ThreadPoolExecutor(max_workers=4) as pool: pdfs = dict(pool.map(get_pdf, sorted(needed)))
        days = [verify_day(files, today + timedelta(days=i), ref, versions, pdfs) for i in range(2)]
        metadata['days'] = days
        metadata['valid'] = all(v['valid'] for day in days for v in day['validation'].values())
        metadata['status'] = 'verified' if metadata['valid'] else 'partial' if any(day['departures'] for day in days) else 'unavailable'
    except Exception as exc:
        metadata['error'] = str(exc)
        metadata['days'] = []
    for i, label in enumerate(('dzis', 'jutro')):
        target = today + timedelta(days=i)
        day = metadata['days'][i] if len(metadata['days']) > i else None
        for slug in STOPS: draw_image(folder / f'{label}-{slug}.png', target, slug, day, metadata.get('error'))
    (folder / 'rozklady.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    write_details(folder, metadata)
    print(json.dumps({k: metadata.get(k) for k in ('status', 'valid', 'date', 'error')}, ensure_ascii=False))
    # Partial is intentional: only independently verified lines have hours.
    return 0 if metadata['status'] in {'verified', 'partial'} else 1


if __name__ == '__main__': sys.exit(main())
