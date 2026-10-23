from datetime import date
import hashlib
import json
from pathlib import Path
import unittest
import board


class BoardSafety(unittest.TestCase):
    def setUp(self):
        self.gref=json.loads((Path(__file__).parent/'gminna-reference.json').read_text())

    def test_municipal_is_never_claimed_to_depart_from_most(self):
        for day,count in [(date(2026,10,23),28),(date(2026,10,24),10),(date(2026,10,25),0),(date(2026,11,11),0)]:
            rows=board.municipal_rows(day,self.gref)
            self.assertEqual(len(rows),count)
            self.assertTrue(all(r['stop']=='dom-ludowy' and r['stop_name']=='Ispina Dom Ludowy' for r in rows))

    def test_municipal_checked_both_directions(self):
        rows=board.municipal_rows(date(2026,10,23),self.gref)
        self.assertEqual([r['time'] for r in rows if r['direction']=='north'][:3],['04:10','05:10','06:20'])
        self.assertEqual([r['time'] for r in rows if r['direction']=='south'][-3:],['20:23','21:48','23:08'])

    def test_changed_a7_pdf_is_not_accepted_as_fallback(self):
        day=date(2026,10,25);url='https://kolejemalopolskie.com.pl/a7.pdf'
        ref={'specs':[{'line':'A7','url':url,'valid_from':str(day),'sha256':hashlib.sha256(b'checked').hexdigest()}]}
        with self.assertRaises(ValueError):board.valid_reference(day,ref,{'A7':[(day,url)]},{url:b'changed'},'A7')

    def test_warsaw_dst_absolute_departure(self):
        self.assertEqual(board.epoch(date(2026,10,24),'06:00')-board.epoch(date(2026,10,23),'06:00'),86400)
        self.assertEqual(board.epoch(date(2026,10,25),'06:00')-board.epoch(date(2026,10,24),'06:00'),90000)

    def test_expired_municipal_is_hidden(self):
        with self.assertRaises(ValueError):board.municipal_rows(date(2026,12,1),self.gref)


if __name__=='__main__':unittest.main()
