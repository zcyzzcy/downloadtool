"""Resolve explicitly shared Bilibili collections into individual video tasks."""
import re
from urllib.parse import parse_qs, urlencode, urlsplit


def resolve_collection(url, fetch_json, check_cancelled):
    parsed = urlsplit(url)
    host = (parsed.hostname or '').lower()
    if host != 'bilibili.com' and not host.endswith('.bilibili.com'):
        return None
    query = parse_qs(parsed.query)
    path = parsed.path
    kind = None
    collection_id = None
    mid = None

    def api(path, **params):
        check_cancelled()
        result = fetch_json('https://api.bilibili.com' + path + '?' + urlencode(params))
        if result.get('code') != 0:
            raise ValueError('B站合集读取失败：%s（%s）' % (result.get('message') or '请在浏览器登录后重试', result.get('code')))
        data = result.get('data')
        if not isinstance(data, dict):
            raise ValueError('B站合集返回了无效数据，请重试')
        return data

    video = re.search(r'/video/(BV[0-9A-Za-z]+|av\d+)', path, re.I)
    title = ''
    if video and query.get('share_from') == ['season']:
        video_id = video.group(1)
        data = api('/x/web-interface/view', **({'aid': video_id[2:]} if video_id.lower().startswith('av') else {'bvid': video_id}))
        season = data.get('ugc_season') or {}
        collection_id = season.get('id')
        mid = season.get('mid') or (data.get('owner') or {}).get('mid')
        title = season.get('title') or ''
        kind = 'season'
        if not collection_id or not mid:
            raise ValueError('这条链接标记为合集分享，但没有读到合集信息，请重新分享合集链接')
    else:
        match = re.search(r'^/(\d+)/lists/(\d+)', path)
        old = re.search(r'^/(\d+)/channel/(collection|series)detail', path)
        favorite = re.search(r'/medialist/detail/ml(\d+)', path)
        if match:
            mid, collection_id = match.groups()
            kind = 'series' if query.get('type') == ['series'] else 'season'
        elif old and query.get('sid'):
            mid, old_kind = old.groups()
            collection_id = query['sid'][0]
            kind = 'series' if old_kind == 'series' else 'season'
        elif '/favlist' in path and query.get('fid'):
            collection_id = query['fid'][0]
            kind = 'favorite'
        elif favorite:
            collection_id = favorite.group(1)
            kind = 'favorite'
        else:
            return None

    entries = []
    seen = set()
    expected = None
    page = 1
    page_size = 100 if kind == 'season' else 20
    while True:
        check_cancelled()
        if kind == 'season':
            data = api('/x/polymer/web-space/seasons_archives_list', mid=mid, season_id=collection_id,
                       page_num=page, page_size=page_size, sort_reverse='false')
            meta = data.get('meta') or {}
            title = title or meta.get('name') or meta.get('title') or 'B站合集'
            count = (data.get('page') or {}).get('total', meta.get('total'))
            videos = data.get('archives') or []
            has_more = None
        elif kind == 'series':
            if page == 1:
                meta_data = api('/x/series/series', series_id=collection_id)
                meta = meta_data.get('meta') or {}
                title = meta.get('name') or 'B站系列'
                expected = int(meta.get('total') or 0)
            data = api('/x/series/archives', mid=mid, series_id=collection_id, only_normal='false',
                       sort='asc', pn=page, ps=page_size)
            count = (data.get('page') or {}).get('total', expected)
            videos = data.get('archives') or []
            has_more = None
        else:
            data = api('/x/v3/fav/resource/list', media_id=collection_id, pn=page, ps=page_size,
                       order='mtime', type=0, tid=0, platform='web')
            info = data.get('info') or {}
            title = info.get('title') or title or 'B站收藏夹'
            count = info.get('media_count', expected)
            videos = data.get('medias') or []
            has_more = data.get('has_more')
        if count is not None:
            count = int(count)
            if expected is not None and count != expected:
                raise ValueError('合集内容在读取时发生变化，请重试，避免漏集')
            expected = count
        before = len(entries)
        for item_index, video in enumerate(videos):
            bvid = video.get('bvid') or video.get('bv_id')
            aid = video.get('aid') or video.get('id')
            non_video = kind == 'favorite' and str(video.get('type', 2)) != '2'
            missing_id = not (bvid and re.fullmatch(r'BV[0-9A-Za-z]+', str(bvid), re.I)) and not (aid and str(aid).isdigit() and int(aid) > 0)
            if kind == 'favorite' and (non_video or missing_id):
                item_id = re.sub(r'[^0-9A-Za-z_-]', '_', str(aid or f'{page}-{item_index}'))
                item_type = re.sub(r'[^0-9A-Za-z_-]', '_', str(video.get('type', 2)))
                target = f'https://www.bilibili.com/medialist/detail/ml{collection_id}#vd-unavailable-{item_type}-{item_id}'
            elif bvid and re.fullmatch(r'BV[0-9A-Za-z]+', str(bvid), re.I):
                target = 'https://www.bilibili.com/video/' + str(bvid)
            elif aid and str(aid).isdigit() and int(aid) > 0:
                target = 'https://www.bilibili.com/video/av' + str(aid)
            else:
                raise ValueError('合集第 %d 页存在无法识别的条目，请重试' % page)
            if target in seen:
                continue
            seen.add(target)
            entry = {'url': target, 'title': video.get('title') or target}
            if non_video:
                entry['error'] = '收藏条目不是视频，无法下载'
            elif missing_id or entry['title'].strip() in ('已失效视频', '已删除视频'):
                entry['error'] = '该视频已失效，其他条目会继续下载'
            entries.append(entry)
        if expected is not None and len(entries) == expected:
            break
        if expected is not None and len(entries) > expected:
            raise ValueError('合集返回的数量与总数不符，请重试')
        if not videos or len(entries) == before or has_more is False:
            if expected is not None and len(entries) != expected:
                raise ValueError('合集列表未读取完整（%d/%d），请重试' % (len(entries), expected))
            break
        if expected is None and has_more is None and len(videos) < page_size:
            break
        page += 1
    if not entries:
        raise ValueError('合集里没有可下载的视频，私密收藏夹请先在内置浏览器登录')
    return {'title': title, 'entries': entries, 'count': len(entries)}
