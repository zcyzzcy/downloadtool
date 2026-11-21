import json
import contextlib
import tempfile
import io
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

root = Path(__file__).resolve().parents[2]
assets = root / 'engine/app/src/main/assets'
sys.path.insert(0, str(assets))
namespace = {'__name__': 'daemon_regression'}
source = (assets / 'vd_daemon.py').read_text(encoding='utf-8')
original_argv = sys.argv
sys.argv = [str(assets / 'vd_daemon.py'), str(assets / 'ytdlp_hot.zipapp'), '']
try:
    exec(compile(source[:source.index("\nemit({'type': 'ready'})")], str(assets / 'vd_daemon.py'), 'exec'), namespace)
finally:
    sys.argv = original_argv


class DaemonTests(unittest.TestCase):
    def tearDown(self):
        namespace['CANCEL'].clear()
        namespace['RUNNING'].clear()

    def test_progress_reports_exact_bytes_speed_and_estimate(self):
        events = []
        with patch.dict(namespace, {'emit': events.append}):
            hook = namespace['hook_factory']('progress-test', '')
            hook({'status':'downloading', 'downloaded_bytes':1234,
                  'total_bytes_estimate':10000, 'speed':256, 'info_dict':{'title':'Test'}})
            hook({'status':'finished','downloaded_bytes':10000,'total_bytes':10000})
        self.assertEqual(events[0]['downloaded_bytes'], 1234)
        self.assertEqual(events[0]['total_bytes'], 10000)
        self.assertEqual(events[0]['speed'], 256)
        self.assertTrue(events[0]['total_estimated'])
        self.assertEqual(events[1]['status'], 'finished')
        self.assertEqual(events[1]['speed'], 0)

    def test_aria2_summary_emits_exact_progress_without_stdout(self):
        events = []
        output = io.StringIO()
        options = {'quiet': True, 'noprogress': True, '_vd_task_id':'real-hook'}
        with contextlib.redirect_stdout(output), patch.dict(namespace, {'emit': events.append}):
            with namespace['TaskYoutubeDL'](options) as ydl:
                downloader = namespace['external_downloader'].Aria2cFD(ydl, options)
                namespace['aria2_call_process'](downloader,
                    [sys.executable, '-c', "print('[#abc 1048576B/8388608B(12%) CN:4 DL:524288B]')"],
                    {'title': 'Test'})
        self.assertEqual(output.getvalue(), '')
        self.assertEqual(events[0]['id'], 'real-hook')
        self.assertEqual(events[0]['downloaded_bytes'], 1048576)
        self.assertEqual(events[0]['total_bytes'], 8388608)
        self.assertEqual(events[0]['speed'], 524288)

    def test_aria2_process_keeps_ownership_in_download_worker(self):
        started = threading.Event()
        errors = []
        class Downloader:
            params = {'_vd_task_id': 'worker-owner'}
        def record(event):
            started.set()
        def worker():
            try:
                namespace['aria2_call_process'](Downloader(), [sys.executable, '-u', '-c',
                    "import time; print('[#abc 1B/100B(1%) CN:1 DL:1B]'); time.sleep(30)"], {})
            except BaseException as error:
                errors.append(error)
        with patch.dict(namespace, {'emit': record}):
            thread = threading.Thread(target=worker, daemon=True)
            thread.start()
            try:
                self.assertTrue(started.wait(5))
                namespace['mark_cancel']('worker-owner')
                thread.join(3)
                self.assertFalse(thread.is_alive())
            finally:
                namespace['mark_cancel']('worker-owner')
                thread.join(3)
        self.assertTrue(all(isinstance(error, namespace['DownloadCancelled']) for error in errors))
        self.assertNotIn('worker-owner', namespace['TASK_PROCESSES'])

    def test_aria2_retries_transient_exit_but_stops_after_three_attempts(self):
        class Downloader:
            params = {'_vd_task_id': 'recover'}
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(namespace, {'emit': events.append}):
            marker = str(Path(directory) / 'attempts.txt')
            script = ("from pathlib import Path; import sys; p=Path(sys.argv[1]); "
                      "n=int(p.read_text())+1 if p.exists() else 1; p.write_text(str(n)); "
                      "print('errorCode=7 errno=28 https://private.test/?token=secret'); "
                      "sys.exit(255 if n<2 else 0)")
            _, _, code = namespace['aria2_call_process'](Downloader(), [sys.executable, '-c', script, marker], {})
            self.assertEqual(code, 0)
            self.assertEqual(Path(marker).read_text(), '2')
            Path(marker).unlink()
            script = script.replace('n<2', 'n<10')
            with self.assertRaisesRegex(RuntimeError, '已尝试 3 次') as failure:
                namespace['aria2_call_process'](Downloader(), [sys.executable, '-c', script, marker], {})
            self.assertEqual(Path(marker).read_text(), '3')
            self.assertIn('errorCode=7', str(failure.exception))
            self.assertIn('errno=28', str(failure.exception))
            self.assertNotIn('private.test', str(failure.exception))
            self.assertTrue(any('第 3/3 次' in event.get('note', '') for event in events))
            self.assertNotIn('secret', str(failure.exception))

    def test_immediate_cancel_is_registered_before_thread_start(self):
        class DeferredThread:
            def __init__(self, **kwargs):
                pass
            def start(self):
                pass
        with patch.object(threading, 'Thread', DeferredThread):
            namespace['handle'](json.dumps({'id': 'queued', 'url': 'https://example.test/video'}))
        namespace['handle'](json.dumps({'id': 'queued', 'action': 'cancel'}))
        self.assertTrue(namespace['is_cancelled']('queued'))

    def test_cancel_external_process_is_prompt_and_isolated(self):
        ready = [threading.Event(), threading.Event()]
        children = {}
        failures = []
        def run(index):
            task_id = 'external-' + str(index)
            threading.current_thread().vd_task_id = task_id
            namespace['RUNNING'].add(task_id)
            try:
                with namespace['TaskPopen']([sys.executable, '-c', 'import time; time.sleep(30)']) as process:
                    children[index] = process
                    ready[index].set()
                    process.wait()
            except BaseException as error:
                failures.append(error)
            finally:
                namespace['RUNNING'].discard(task_id)
        threads = [threading.Thread(target=run, args=(i,), daemon=True) for i in range(2)]
        for thread in threads:
            thread.start()
        try:
            self.assertTrue(all(event.wait(5) for event in ready))
            start = time.monotonic()
            namespace['handle'](json.dumps({'action': 'cancel', 'id': 'external-0'}))
            threads[0].join(3)
            self.assertFalse(threads[0].is_alive())
            self.assertLess(time.monotonic() - start, 3)
            self.assertIsNone(children[1].poll(), 'Cancelling one task killed another task')
        finally:
            for child in children.values():
                if child.poll() is None:
                    child.kill()
            for thread in threads:
                thread.join(3)
        self.assertEqual(failures, [])
        self.assertEqual(namespace['TASK_PROCESSES'], {})

    def test_cancel_at_completion_never_reports_done(self):
        events = []
        class FakeYoutubeDL:
            def __init__(self, params):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
                namespace['mark_cancel']('finishing')
                return {'requested_downloads': []}
        with patch.dict(namespace, {'TaskYoutubeDL': FakeYoutubeDL, 'emit': events.append}):
            namespace['run_download']({'id': 'finishing', 'url': 'https://example.test/video'})
        self.assertEqual([event['type'] for event in events], ['error'])
        self.assertNotIn('finishing', namespace['RUNNING'])

    def test_collection_returns_entries_and_releases_slot(self):
        events = []
        entries = [{'url': 'https://www.bilibili.com/video/BV1test000001', 'title': 'Episode'}]
        def resolve(url, fetch, check):
            check()
            return {'title': 'Collection', 'entries': entries, 'count': 1}
        before = namespace['SLOTS']._value
        with patch.dict(namespace, {'resolve_collection': resolve, 'emit': events.append}):
            namespace['run_download']({'id': 'collection', 'url': 'https://www.bilibili.com/video/BV1test000001?share_from=season', 'resolve_collection': True})
        self.assertEqual(events[0]['type'], 'collection')
        self.assertEqual(events[0]['entries'], entries)
        self.assertEqual(namespace['SLOTS']._value, before)


    def test_sniffed_direct_media_uses_downloader_without_extractor(self):
        events, calls = [], []
        class FakeYoutubeDL:
            def __init__(self, params):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, *args, **kwargs):
                raise AssertionError('Known media must bypass page extraction')
            def process_ie_result(self, info, download):
                calls.append(info)
                return {'requested_downloads': [{'filepath': '/tmp/Page title.mp4'}]}
        request = {'id': 'direct-test', 'url': 'https://cdn.example.test/file.MP4?token=123',
                   'direct_media': True, 'title': 'Page title',
                   'headers': {'Referer': 'https://example.test/post'}}
        with patch.dict(namespace, {'TaskYoutubeDL': FakeYoutubeDL, 'emit': events.append}):
            namespace['run_download'](request)
        self.assertEqual(calls[0]['url'], request['url'])
        self.assertEqual(calls[0]['ext'], 'mp4')
        self.assertEqual(calls[0]['webpage_url'], request['headers']['Referer'])
        self.assertEqual(events[-1]['files'], ['Page title.mp4'])
        self.assertEqual(events[-1]['type'], 'done')

    def test_direct_media_rejects_successful_login_page_response(self):
        events = []
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'login.mp4'
            target.write_text('<!DOCTYPE html><html>Login required</html>')
            class FakeYoutubeDL:
                def __init__(self, params):
                    pass
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    pass
                def process_ie_result(self, info, download):
                    return {'requested_downloads': [{'filepath': str(target)}]}
            with patch.dict(namespace, {'TaskYoutubeDL': FakeYoutubeDL, 'emit': events.append}):
                namespace['run_download']({'id': 'expired-media', 'url': 'https://cdn.test/login.mp4', 'direct_media': True})
            self.assertEqual(events[-1]['type'], 'error')
            self.assertFalse(target.exists())

    def test_direct_media_requires_flag_scheme_and_supported_extension(self):
        info = namespace['direct_media_info']
        self.assertIsNone(info({'url': 'https://cdn.test/video.mp4'}))
        for url in ['file:///data/video.mp4', 'https://example.test/watch',
                    'https://cdn.test/master.m3u8', 'https://cdn.test/video.mpd']:
            self.assertIsNone(info({'url': url, 'direct_media': True}))
        self.assertEqual(info({'url': 'https://cdn.test/test%20audio.M4A?key=1',
                               'direct_media': True})['title'], 'test audio')


if __name__ == '__main__':
    unittest.main()
