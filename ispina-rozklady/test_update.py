from datetime import date
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('timetable', Path(__file__).with_name('update.py'))
u = importlib.util.module_from_spec(spec); spec.loader.exec_module(u)


def feed():
    stops = []
    for s in u.STOPS.values():
        for direction in ('north', 'south'):
            stops.append({'stop_id': s[direction], 'stop_name': s['source_name'], 'parent_station': s['parent'],
                          'location_type': '0', 'stop_lat': '50.116', 'stop_lon': '20.390'})
    return {'feed_info.txt': [{'feed_start_date': '20261001', 'feed_end_date': '20261130'}],
            'stops.txt': stops, 'routes.txt': [{'route_id': x, 'route_short_name': x} for x in u.ROUTES],
            'trips.txt': [{'trip_id': 't', 'route_id': 'A4', 'service_id': 'regular'}],
            'calendar.txt': [{'service_id': 'regular', 'start_date': '20261001', 'end_date': '20261130',
                              **{d: '1' for d in ('monday','tuesday','wednesday','thursday','friday','saturday','sunday')}}],
            'calendar_dates.txt': [],
            'stop_times.txt': [{'trip_id': 't', 'stop_id': '119', 'stop_sequence': '1', 'departure_time': '06:00:00'}]}


class SafetyTests(unittest.TestCase):
    def test_calendar_removes_and_adds(self):
        f = feed(); f['calendar_dates.txt'] = [{'service_id': 'regular', 'date': '20261025', 'exception_type': '2'},
                                              {'service_id': 'special', 'date': '20261025', 'exception_type': '1'}]
        self.assertEqual(u.active_services(f, date(2026,10,25)), {'special'})

    def test_calendar_dates_only(self):
        f = feed(); f['calendar.txt'] = []; f['calendar_dates.txt'] = [{'service_id': 'x', 'date': '20261025', 'exception_type': '1'}]
        self.assertEqual(u.active_services(f, date(2026,10,25)), {'x'})
        self.assertEqual(u.active_services(f, date(2026,10,26)), set())

    def test_invalid_minutes_seconds_and_format(self):
        for value in ('06:-1:00','06:60:00','06:00:60','-1:00:00','48:00:00','06:00:01','x',''):
            with self.subTest(value=value), self.assertRaises(ValueError): u.parse_time(value)

    def test_overnight_belongs_to_next_civil_day_once(self):
        f = feed(); f['stop_times.txt'][0]['departure_time'] = '24:10:00'
        r = u.raw_departures(f, date(2026,10,25))
        self.assertEqual(len(r), 1); self.assertEqual(r[0]['time'], '00:10')

    def test_drop_off_only_is_not_a_departure(self):
        f = feed(); f['stop_times.txt'][0]['pickup_type'] = '1'
        self.assertEqual(u.raw_departures(f, date(2026,10,25)), [])

    def test_station_identity_change_fails(self):
        f = feed(); f['stops.txt'][0]['parent_station'] = 'different'
        with self.assertRaises(ValueError): u.validate_stops(f)

    def test_outside_feed_coverage_fails(self):
        with self.assertRaises(ValueError): u.raw_departures(feed(), date(2026,12,1))

    def test_clock_change_uses_warsaw_civil_date(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        for utc, expected in [('2026-10-24T22:30:00+00:00','2026-10-25'), ('2026-10-25T23:30:00+00:00','2026-10-26')]:
            self.assertEqual(datetime.fromisoformat(utc).astimezone(u.TZ).date().isoformat(), expected)
        self.assertEqual(u.day_type(date(2026,11,11)), 'sunday')

    def test_missing_or_ambiguous_feed_link_fails(self):
        for html in ('<a href="x">mld-gtfs.zip</a>', '<a href="a">GTFS.zip</a><a href="b">GTFS.zip</a>'):
            with self.assertRaises(ValueError): u.discover_feed(html)

    def test_untrusted_download_host_fails_before_network(self):
        for url in ('http://gtfs.kolejemalopolskie.com.pl/a','https://example.com/a'):
            with self.assertRaises(ValueError): u.download(url)

    def test_expired_review_fails_closed(self):
        with self.assertRaises(ValueError):
            u.verify_day(feed(), date(2026,12,1), {'reviewed_at':'2026-10-23','review_valid_until':'2026-11-30'}, {}, {})

    def test_pdf_mismatch_hides_entire_line(self):
        import hashlib
        day = date(2026,10,25); body = b'reviewed PDF'
        refs = {'reviewed_at':'2026-10-23','review_valid_until':'2026-11-30','specs':[]}
        versions = {}; pdfs = {}
        for line in u.ROUTES:
            url = 'https://kolejemalopolskie.com.pl/' + line + '.pdf'; versions[line] = [(day,url)]; pdfs[url] = body
            refs['specs'].append({'line':line,'url':url,'valid_from':str(day),'sha256':hashlib.sha256(body).hexdigest(),'rows':[]})
        r = u.verify_day(feed(),day,refs,versions,pdfs)
        self.assertFalse(r['validation']['A4']['valid']); self.assertEqual(r['departures'], [])
        self.assertEqual(r['validation']['A4']['differences'][0]['only_gtfs'], ['06:00'])

    def test_changed_pdf_hash_hides_hours(self):
        import hashlib
        day = date(2026,10,25)
        refs = {'reviewed_at':'2026-10-23','review_valid_until':'2026-11-30','specs':[]}; versions={}; pdfs={}
        for line in u.ROUTES:
            url='https://kolejemalopolskie.com.pl/'+line+'.pdf'; versions[line]=[(day,url)]; pdfs[url]=b'changed'
            refs['specs'].append({'line':line,'url':url,'valid_from':str(day),'sha256':hashlib.sha256(b'original').hexdigest(),'rows':[]})
        r=u.verify_day(feed(),day,refs,versions,pdfs)
        self.assertEqual(r['departures'], []); self.assertFalse(any(v['valid'] for v in r['validation'].values()))

    def test_failed_generation_replaces_stale_images_and_tables(self):
        import json
        import tempfile
        from unittest.mock import patch
        from PIL import Image
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); out = root / 'out'; out.mkdir()
            (root / 'bad.zip').write_bytes(b'not a zip')
            (root / 'page.html').write_text('')
            (out / 'old.png').write_bytes(b'old hours')
            (out / 'README.md').write_text('old hours')
            (out / 'rozklady.json').write_text('{"valid":true,"days":["old"]}')
            argv = ['update.py','--date','2026-10-23','--input-zip',str(root/'bad.zip'),
                    '--offline-directory',str(root),'--output',str(out)]
            with patch('sys.argv', argv): self.assertEqual(u.main(), 1)
            data = json.loads((out/'rozklady.json').read_text())
            self.assertFalse(data['valid']); self.assertEqual(data['days'], [])
            self.assertNotIn('old hours', (out/'README.md').read_text())
            self.assertFalse((out/'old.png').exists())
            self.assertEqual(len(list(out.glob('*.png'))), 6)
            for path in out.glob('*.png'):
                with Image.open(path) as image: image.verify()


if __name__ == '__main__': unittest.main()
