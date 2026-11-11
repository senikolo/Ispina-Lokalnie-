import io
import json
import unittest
import urllib.error
from datetime import datetime, timezone

from verify_youtube_service import CHANNEL, verify_service


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, cors='*'):
        super().__init__(json.dumps(data).encode())
        self.headers = {'Content-Type':'application/json', 'Access-Control-Allow-Origin':cors}


def page(ids, token=None):
    return {'schema':1, 'channel':{'id':CHANNEL}, 'uploadsPlaylistId':'UUofficialPlaylist',
            'fetchedAt':datetime.now(timezone.utc).isoformat(), 'nextPageToken':token,
            'items':[{'id':value, 'title':'Film kanału', 'embeddable':True} for value in ids]}


class LiveServiceGateTests(unittest.TestCase):
    def test_actual_failure_class_is_not_treated_as_an_empty_channel(self):
        def missing(*args, **kwargs):
            raise urllib.error.HTTPError('https://example.test/',404,'Missing',{},None)
        with self.assertRaisesRegex(ValueError,'nie została wdrożona.*404'):
            verify_service(missing)

    def test_complete_list_requires_fresh_correct_channel_and_embeddable_video(self):
        calls = []
        def opener(url, **kwargs):
            calls.append(url)
            return Response(page(['ccccccccccc']) if 'pageToken=' in url else page(['aaaaaaaaaaa','bbbbbbbbbbb'],'PAGE2'))
        result = verify_service(opener)
        self.assertEqual(result['publicVideos'],3)
        self.assertEqual(len(calls),2)
        wrong = page(['aaaaaaaaaaa'])
        wrong['channel']['id'] = 'another-channel'
        with self.assertRaisesRegex(ValueError,'właściwego kanału'):
            verify_service(lambda *args, **kwargs:Response(wrong))

    def test_bad_cors_or_no_playable_videos_blocks_delivery(self):
        with self.assertRaisesRegex(ValueError,'nie zezwala'):
            verify_service(lambda *args, **kwargs:Response(page(['aaaaaaaaaaa']),cors='https://other.test'))
        empty = page(['aaaaaaaaaaa'])
        empty['items'][0]['embeddable'] = False
        with self.assertRaisesRegex(ValueError,'zezwoleniem'):
            verify_service(lambda *args, **kwargs:Response(empty))


if __name__ == '__main__':
    unittest.main()
