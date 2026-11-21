"""Extract only media delivered by the official post API for the current session."""
import re
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit

from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError


class PostMedia(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ('video', 'audio', 'source'):
            value = attrs.get('src')
            if value:
                self.urls.append(value)
        elif tag == 'a' and 'attachment_download_link' in attrs.get('class', '').split():
            self.urls.append(attrs.get('href', ''))


def post_media(data, page_url):
    if data.get('qualified') is False:
        raise ExtractorError('该作品尚未解锁，请在应用浏览器中登录已购买或有权限的账号后重试', expected=True)
    parser = PostMedia()
    parser.feed(data.get('content') or '')
    urls = list(parser.urls)
    for field in ('video', 'attachment'):
        items = data.get(field) or []
        if isinstance(items, list):
            urls.extend(item.get('url', '') for item in items if isinstance(item, dict))
    result = []
    for value in urls:
        url = urljoin(page_url, value)
        if urlsplit(url).scheme not in ('http', 'https'):
            continue
        if not re.search(r'\.(mp4|m4v|mov|webm|mkv|m3u8|mpd|mp3|m4a|wav|flac|aac|ogg)(?:$|[?#])', url, re.I):
            continue
        if url not in result:
            result.append(url)
    if not result:
        raise ExtractorError('该作品当前没有可下载的正文媒体，请确认登录状态及作品内容', expected=True)
    return result


class ZhuliwaIE(InfoExtractor):
    IE_NAME = 'zhuliwa'
    _VALID_URL = r'https?://(?:www\.)?zhuliwa\.com/post/(?P<id>[0-9a-fA-F-]{36})(?:[/?#]|$)'

    def _real_extract(self, url):
        post_id = self._match_id(url)
        response = self._download_json(
            'https://zhuliwa.com/api/post/get', post_id, data=b'',
            query={'postId': post_id}, headers={'Referer': url})
        if response.get('code') != 200:
            raise ExtractorError('助力蛙获取作品失败，请检查登录状态后重试', expected=True)
        data = response.get('data')
        if not isinstance(data, dict):
            raise ExtractorError('助力蛙作品数据不完整，请稍后重试', expected=True)
        urls = post_media(data, url)
        title = data.get('title') or post_id
        entries = []
        for index, media_url in enumerate(urls, 1):
            media_title = title if len(urls) == 1 else f'{title} ({index})'
            path = urlsplit(media_url).path.lower()
            headers = {'Referer': url}
            if path.endswith('.m3u8'):
                formats = self._extract_m3u8_formats(media_url, post_id, 'mp4', headers=headers)
            elif path.endswith('.mpd'):
                formats = self._extract_mpd_formats(media_url, post_id, headers=headers)
            else:
                formats = [{'url': media_url}]
            entries.append({'id': f'{post_id}-{index}', 'title': media_title,
                            'formats': formats, 'http_headers': headers})
        if len(entries) == 1:
            return entries[0]
        return self.playlist_result(entries, post_id, title)
