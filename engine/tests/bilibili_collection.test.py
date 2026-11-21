import sys
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/assets'))
from bilibili_collection import resolve_collection


def video(index):
    return {'bvid': 'BV1test%05d' % index, 'title': '第%d集' % index}


class CollectionTests(unittest.TestCase):
    def test_shared_video_resolves_all_22_entries(self):
        def fetch(url):
            if '/view?' in url:
                return {'code': 0, 'data': {'owner': {'mid': 123}, 'ugc_season': {'id': 456, 'title': '合集'}}}
            self.assertEqual(parse_qs(urlsplit(url).query)['season_id'], ['456'])
            return {'code': 0, 'data': {'meta': {'name': '合集'}, 'page': {'total': 22},
                                      'archives': [video(i) for i in range(22)]}}
        result = resolve_collection('https://www.bilibili.com/video/BV1234567890?share_from=season', fetch, lambda: None)
        self.assertEqual(result['count'], 22)
        self.assertEqual(len({e['url'] for e in result['entries']}), 22)

    def test_regular_video_stays_single(self):
        def fail(url):
            self.fail('Ordinary video must not fetch a collection')
        self.assertIsNone(resolve_collection('https://www.bilibili.com/video/BV1234567890?p=2', fail, lambda: None))
        self.assertIsNone(resolve_collection('https://bilibili.com.evil.test/1/lists/2', fail, lambda: None))

    def test_collection_paginates_and_preserves_order(self):
        calls = []
        def fetch(url):
            page = int(parse_qs(urlsplit(url).query)['page_num'][0])
            calls.append(page)
            items = range((page - 1) * 100, min(page * 100, 205))
            return {'code': 0, 'data': {'page': {'total': 205}, 'meta': {'name': '分页'},
                                      'archives': [video(i) for i in items]}}
        result = resolve_collection('https://space.bilibili.com/1/lists/2', fetch, lambda: None)
        self.assertEqual(calls, [1, 2, 3])
        self.assertEqual(result['count'], 205)
        self.assertEqual(result['entries'][-1]['title'], '第204集')

    def test_incomplete_or_repeated_pages_fail(self):
        for items in [[], [video(0)]]:
            with self.subTest(items=items):
                def fetch(url):
                    return {'code': 0, 'data': {'page': {'total': 22}, 'archives': items}}
                with self.assertRaisesRegex(ValueError, '未读取完整'):
                    resolve_collection('https://space.bilibili.com/1/lists/2', fetch, lambda: None)

    def test_favorite_paginates_and_keeps_unavailable_entries(self):
        def fetch(url):
            page = int(parse_qs(urlsplit(url).query)['pn'][0])
            items = [dict(video(i), type=2) for i in range((page - 1) * 20, min(page * 20, 23))]
            if page == 2:
                items[0]['title'] = '已失效视频'
            return {'code': 0, 'data': {'info': {'media_count': 23, 'title': '收藏夹'},
                                      'medias': items, 'has_more': page == 1}}
        result = resolve_collection('https://space.bilibili.com/1/favlist?fid=99', fetch, lambda: None)
        self.assertEqual(result['count'], 23)
        self.assertIn('error', result['entries'][20])

    def test_non_video_id_cannot_hide_real_video_or_abort_favorite(self):
        data = {'code': 0, 'data': {'info': {'media_count': 3, 'title': 'Mixed'},
            'has_more': False, 'medias': [
                {'id': 5, 'type': 12, 'title': 'Non-video'},
                {'id': 5, 'type': 2, 'title': 'Video'},
                {'id': 0, 'type': 2, 'title': '已失效视频'}]}}
        result = resolve_collection('https://space.bilibili.com/1/favlist?fid=99', lambda url: data, lambda: None)
        self.assertEqual(result['count'], 3)
        entries = result['entries']
        self.assertEqual(entries[1]['url'], 'https://www.bilibili.com/video/av5')
        self.assertNotIn('error', entries[1])
        self.assertIn('#vd-unavailable-12-5', entries[0]['url'])
        self.assertIn('error', entries[0])
        self.assertIn('error', entries[2])

    def test_valid_title_mentioning_expiration_is_not_marked_unavailable(self):
        data = {'code': 0, 'data': {'page': {'total': 1}, 'meta': {'name': 'Test'},
            'archives': [{'bvid': 'BV1test00000', 'title': '如何修复已失效链接'}]}}
        result = resolve_collection('https://space.bilibili.com/1/lists/2', lambda url: data, lambda: None)
        self.assertNotIn('error', result['entries'][0])

    def test_permission_error_is_explicit(self):
        with self.assertRaisesRegex(ValueError, '登录'):
            resolve_collection('https://www.bilibili.com/medialist/detail/ml99',
                               lambda url: {'code': -101, 'message': '账号未登录'}, lambda: None)

    def test_cancel_stops_before_network_request(self):
        def cancel():
            raise InterruptedError('cancelled')
        with self.assertRaises(InterruptedError):
            resolve_collection('https://space.bilibili.com/1/lists/2', lambda url: self.fail('Network called'), cancel)


if __name__ == '__main__':
    unittest.main()
