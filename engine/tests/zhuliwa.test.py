import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

assets = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
sys.path[:0] = [str(assets), str(assets / 'ytdlp_hot.zipapp')]
import yt_dlp
from yt_dlp.utils import ExtractorError
from zhuliwa import ZhuliwaIE, post_media

PAGE = 'https://zhuliwa.com/post/1b1f6484-936a-42e0-89ac-e5168b5f8e1d'


class ZhuliwaTests(unittest.TestCase):
    def test_exact_host(self):
        self.assertTrue(ZhuliwaIE.suitable(PAGE))
        self.assertFalse(ZhuliwaIE.suitable(PAGE.replace('zhuliwa.com', 'zhuliwa.com.evil.test')))

    def test_locked_post_rejects_even_if_cover_or_url_is_present(self):
        with self.assertRaisesRegex(ExtractorError, '尚未解锁'):
            post_media({'qualified': False, 'content': '<video src="/old.mp4"></video>',
                        'coverImage': '/cover.jpg'}, PAGE)

    def test_content_and_uploads_deduplicate_and_exclude_cover(self):
        result = post_media({'qualified': True,
            'content': '<img src="/cover.jpg"><video poster="/poster.jpg"><source src="//cdn.zhuliwa.com/one.mp4?a=1&amp;b=2"></video>',
            'video': [{'url': 'https://cdn.zhuliwa.com/one.mp4?a=1&b=2'}, {'url': '/two.m3u8'}],
            'attachment': [{'url': '/track.mp3'}, {'url': '/archive.zip'}, {'url': 'javascript:alert(1)'}]}, PAGE)
        self.assertEqual(result, ['https://cdn.zhuliwa.com/one.mp4?a=1&b=2',
                                 'https://zhuliwa.com/two.m3u8', 'https://zhuliwa.com/track.mp3'])

    def test_empty_post_is_explicit_error(self):
        with self.assertRaisesRegex(ExtractorError, '没有可下载'):
            post_media({'qualified': True, 'coverImage': '/cover.jpg'}, PAGE)

    def test_real_downloader_saves_every_video_and_sends_source(self):
        requests = []
        payload = b'controlled media fixture' * 200
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((self.path, self.headers.get('Referer'), self.headers.get('Cookie')))
                self.send_response(200)
                self.send_header('Content-Type', 'video/mp4')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f'http://127.0.0.1:{server.server_port}'
        response = {'code': 200, 'data': {'qualified': True, 'title': 'Public 50% fixture',
            'video': [{'url': origin + '/one.mp4'}, {'url': origin + '/two.mp4'}]}}
        try:
            with tempfile.TemporaryDirectory() as directory:
                options = {'quiet': True, 'noprogress': True, 'noplaylist': True, 'format': 'best',
                           'outtmpl': directory + '/%(title)s-%(id)s.%(ext)s'}
                with yt_dlp.YoutubeDL(options) as ydl:
                    ydl.cookiejar.set_cookie(__import__('http.cookiejar', fromlist=['Cookie']).Cookie(
                        0, 'audit_session', 'ok', None, False, '127.0.0.1', False, False,
                        '/', True, False, None, True, None, None, {}))
                    ydl.add_info_extractor(ZhuliwaIE())
                    with patch.object(ZhuliwaIE, '_download_json', return_value=response):
                        info = ydl.extract_info(PAGE, download=True, ie_key=ZhuliwaIE.ie_key())
                files = list(Path(directory).glob('*.mp4'))
                self.assertEqual(len(info['entries']), 2)
                self.assertEqual(len(files), 2)
                self.assertTrue(all(file.read_bytes() == payload for file in files))
                self.assertEqual(len(requests), 2)
                self.assertTrue(all(ref == PAGE and 'audit_session=ok' in cookie
                                    for _, ref, cookie in requests))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
