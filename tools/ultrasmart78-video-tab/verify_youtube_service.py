#!/usr/bin/env python3
"""Release gate: do not hand out another APK backed by a missing video service."""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

CHANNEL = 'UCeeKoKHiH-rGyQOZn6wW-7A'
ENDPOINT = 'https://ispina-most-odjazdy.vercel.app/api/youtube-videos'


def verify_service(opener=urllib.request.urlopen):
    token = None
    seen = set()
    ids = set()
    playable = set()
    playlist = None
    while True:
        url = ENDPOINT + ('?pageToken=' + urllib.parse.quote(token, safe='') if token else '')
        try:
            with opener(url, timeout=25) as response:
                if response.status != 200 or 'application/json' not in response.headers.get('Content-Type', ''):
                    raise ValueError('Usługa listy filmów nie zwraca prawidłowej odpowiedzi JSON.')
                cors = response.headers.get('Access-Control-Allow-Origin')
                if cors not in ('*', 'https://ispinalokalnie.wordpress.com'):
                    raise ValueError('Usługa listy nie zezwala na odczyt przez aplikację.')
                body = response.read(500001)
                if len(body) > 500000:
                    raise ValueError('Odpowiedź listy filmów jest zbyt duża.')
                data = json.loads(body)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise ValueError('Usługa listy filmów nie została wdrożona (HTTP 404).') from None
            raise ValueError('Usługa listy filmów jest niedostępna (HTTP ' + str(error.code) + '). Sprawdź klucz i wdrożenie.') from None
        except (urllib.error.URLError, TimeoutError):
            raise ValueError('Nie można połączyć się z usługą listy filmów.') from None
        if data.get('schema') != 1 or data.get('channel', {}).get('id') != CHANNEL or not isinstance(data.get('items'), list):
            raise ValueError('Usługa nie zwraca listy właściwego kanału.')
        current_playlist = data.get('uploadsPlaylistId')
        if not isinstance(current_playlist, str) or not re.fullmatch(r'[A-Za-z0-9_-]{10,80}', current_playlist):
            raise ValueError('Usługa nie wskazuje playlisty przesłanych filmów.')
        if playlist and current_playlist != playlist:
            raise ValueError('Playlista zmieniła się w trakcie pobierania.')
        playlist = current_playlist
        try:
            timestamp = datetime.fromisoformat(data['fetchedAt'].replace('Z', '+00:00'))
            age = (datetime.now(timezone.utc) - timestamp).total_seconds()
            if not -300 <= age <= 600:
                raise ValueError()
        except (KeyError, TypeError, ValueError):
            raise ValueError('Usługa nie zwraca świeżej listy filmów.') from None
        for item in data['items']:
            if (not isinstance(item, dict) or not re.fullmatch(r'[A-Za-z0-9_-]{11}', str(item.get('id', '')))
                    or not isinstance(item.get('title'), str) or type(item.get('embeddable')) is not bool):
                raise ValueError('Nieprawidłowa pozycja na liście filmów.')
            ids.add(item['id'])
            if item['embeddable']:
                playable.add(item['id'])
        token = data.get('nextPageToken')
        if token is None:
            break
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_=-]{1,512}', token) or token in seen:
            raise ValueError('Nieprawidłowe stronicowanie listy filmów.')
        seen.add(token)
    if not playable:
        raise ValueError('API nie zwróciło żadnego filmu z zezwoleniem na odtwarzanie w aplikacji.')
    return {'channel': CHANNEL, 'publicVideos': len(ids), 'embeddableVideos': len(playable), 'uploadsPlaylistId': playlist}


if __name__ == '__main__':
    try:
        print(json.dumps(verify_service(), ensure_ascii=False))
    except (ValueError, json.JSONDecodeError) as error:
        print('APK nie jest gotowy do przekazania: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
