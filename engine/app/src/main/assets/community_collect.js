// Site-specific extraction. Read the selected post only; never use the feed,
// comments, cover metadata or network image requests as a substitute for it.
function vdCollectCommunity(site) {
  const fail = error => JSON.stringify({media: null, error});
  const pending = () => JSON.stringify({media: null});
  const storage = key => { try { return localStorage.getItem(key) || ''; } catch (_) { return ''; } };
  // Keep requests tied to this exact post. A second phase (video URLs) has its
  // own key so polling never restarts an already finished manifest request.
  const jsonRequest = (key, url, options = {}) => {
    const requests = window.__vdCommunityRequests || (window.__vdCommunityRequests = {});
    if (!requests[key]) {
      const cache = requests[key] = {pending: true};
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 15000);
      fetch(url, {...options, signal: controller.signal})
        .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(data => { cache.data = data; })
        .catch(() => { cache.error = '正文加载失败，请打开来源网页确认是否需要登录，或稍后重试'; })
        .finally(() => { clearTimeout(timer); cache.pending = false; });
    }
    return requests[key];
  };
  const http = value => {
    if (typeof value !== 'string' || !/^(?:https?:)?\/\//i.test(value)) return '';
    try { const u = new URL(value, location.href); return /^https?:$/.test(u.protocol) ? u.href : ''; }
    catch (_) { return ''; }
  };
  const pack = (images, videos, desc, expected) => {
    const resolved = images.filter(Boolean).length + videos.filter(Boolean).length;
    if (expected && resolved !== expected)
      return fail('正文媒体清单不完整，暂未下载。请登录来源网站后重试');
    images = [...new Set(images.filter(Boolean))];
    videos = [...new Set(videos.filter(Boolean))];
    if (!images.length && !videos.length) return fail('当前正文没有可直接下载的图片或视频；嵌入视频可能需要打开原平台链接');
    if (images.length + videos.length > 500) return fail('正文超过 500 项，请分批下载，未截断保存');
    const streams = videos.filter(u => /\.(?:m3u8|mpd)(?:[?#]|$)/i.test(u));
    videos = videos.filter(u => !streams.includes(u));
    return JSON.stringify({media: {images, videos, streams, desc: String(desc || document.title || '帖子').slice(0, 180), complete: true}});
  };
  try {
    if (site === 'miyoushe') {
      const match = location.pathname.match(/\/article\/(\d+)/);
      if (!match) return fail('请分享米游社或 HoYoLAB 的具体文章链接；合集、主页暂不支持整批展开');
      const id = match[1];
      const overseas = /(?:^|\.)hoyolab\.com$/i.test(new URL(location.href).hostname);
      const api = overseas ? 'https://bbs-api-os.hoyolab.com/community/post/wapi/getPostFull'
        : 'https://bbs-api.miyoushe.com/post/wapi/getPostFull';
      const cache = jsonRequest('miyoushe:' + id, api + '?post_id=' + id, {credentials: 'include'});
      if (cache.pending) return pending();
      if (cache.error) return fail(cache.error);
      const data = cache.data;
      if (!data || data.retcode !== 0) return fail('文章不可访问，可能已删除、需要登录或站点要求验证，请打开来源网页确认');
      const post = data.data && data.data.post;
      if (!post || !post.post || String(post.post.post_id) !== id) return fail('文章编号不匹配，未下载其他内容');
      if (post.post.is_missing) return fail('文章正文不完整，请登录后重试');
      const imageList = post.image_list || [];
      const vods = post.vod_list || [];
      if (imageList.some(i => i.is_deleted) || (post.post.images || []).length > imageList.length)
        return fail('文章的图片清单不完整或部分图片已删除，未标记为完整下载');
      const images = imageList.filter(i => !i.is_deleted).map(i => http(i.url));
      const videos = vods.map(v => {
        const resolutions = (v.resolutions || []).filter(r => http(r.url));
        resolutions.sort((a, b) => ((b.width || 0) * (b.height || 0) - (a.width || 0) * (a.height || 0))
          || ((b.bitrate || 0) - (a.bitrate || 0)));
        return http(resolutions.length ? resolutions[0].url : v.url);
      });
      return pack(images, videos, post.post.subject, images.length + vods.length);
    }
    if (site === 'kurobbs') {
      const page = new URL(location.href);
      const match = page.pathname.match(/\/post\/(\d+)/);
      const id = (match && match[1]) || page.searchParams.get('postId');
      if (!id || !/^\d+$/.test(id)) return fail('请分享库街区的具体帖子链接；主页、合集暂不支持整批展开');
      const headers = {'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8', source: 'h5', version: '2.2.0'};
      const device = storage('dc');
      if (device) headers.devCode = device;
      const token = storage('auth_token');
      if (token && !/[\x00\r\n]/.test(token)) headers.token = token;
      const cache = jsonRequest('kurobbs:' + id, 'https://api.kurobbs.com/forum/getPostDetail', {
        method: 'POST', headers, body: 'postId=' + id + '&isOnlyPublisher=0&showOrderType=2',
      });
      if (cache.pending) return pending();
      if (cache.error) return fail(cache.error);
      const data = cache.data;
      if (!data || data.code !== 200) return fail('库街区帖子暂不可访问，请打开来源网页确认登录、验证或帖子状态');
      const post = data.data && data.data.postDetail;
      if (!post || String(post.id) !== id) return fail('帖子编号不匹配，未下载其他内容');
      // Text, dividers, link-card icons and comment media are deliberately excluded.
      const images = (post.postContent || []).filter(p => p.contentType === 2).map(p => p.isAbnormal ? '' : http(p.url));
      let videos = [];
      if (post.videoId) {
        const url = http(post.videoUrl || post.playUrl);
        if (!url) return fail('此库街区视频需要网页播放器的播放凭证，暂不能自动下载；未将视频封面保存成视频');
        videos = [url];
      }
      return pack(images, videos, post.postTitle, images.length + videos.length);
    }
    if (site === 'taptap') {
      const match = location.pathname.match(/\/moment\/(\d+)/);
      if (!match) return fail('请分享 TapTap 的具体动态链接；论坛列表、游戏主页和合集暂不支持整批展开');
      const id = match[1];
      const device = window.__vdTapDevice || (window.__vdTapDevice = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.floor(Math.random() * 16); return (c === 'x' ? r : (r & 3 | 8)).toString(16);
      }));
      const xua = 'V=1&PN=WebApp&LANG=zh_CN&VN_CODE=102&LOC=CN&PLT=PC&DS=Android&UID=' + device + '&OS=Windows&OSV=10&DT=PC';
      const suffix = '&X-UA=' + encodeURIComponent(xua);
      const cache = jsonRequest('taptap:' + id, 'https://www.taptap.cn/webapiv2/moment/v3/detail?id=' + id + suffix);
      if (cache.pending) return pending();
      if (cache.error) return fail(cache.error);
      const data = cache.data && cache.data.data;
      const moment = data && data.moment;
      if (!moment || String(moment.id_str) !== id) return fail('TapTap 动态不可访问或编号不匹配，请打开来源网页确认');
      const first = data.first_post;
      if (!first || !first.is_topic || !first.contents || !Array.isArray(first.contents.json))
        return fail('未取得完整动态正文，请登录后重试，未改用摘要或评论图片');
      const images = [], videoIds = [];
      const walk = node => {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) { node.forEach(walk); return; }
        if (node.type === 'image') {
          const info = node.info || {}, image = info.image || {};
          images.push(http(image.original_url || info.img_url || image.url));
          return;
        }
        if (node.type === 'video') {
          videoIds.push(String(node.info && node.info.video_id || ''));
          return; // The video's image is a cover, not a separate gallery image.
        }
        // Only document nodes, not arbitrary objects or link-preview payloads.
        if (node.children) walk(node.children);
      };
      walk(first.contents.json);
      for (const image of first.footer_images || []) images.push(http(image.original_url || image.url));
      const ids = [...new Set(videoIds)];
      if (ids.some(id => !/^\d+$/.test(id))) return fail('正文有无法识别的视频，未标记为完整下载');
      let videos = [];
      if (ids.length) {
        const resources = jsonRequest('taptap-video:' + id, 'https://www.taptap.cn/webapiv2/video-resource/v1/multi-get?video_ids=' + ids.join(',') + suffix);
        if (resources.pending) return pending();
        if (resources.error) return fail(resources.error);
        const list = resources.data && resources.data.data && resources.data.data.list || [];
        videos = ids.map(id => {
          const video = list.find(v => String(v.video_id) === id);
          return http(video && video.play_url && video.play_url.url);
        });
      }
      return pack(images, videos, moment.topic && moment.topic.title, images.length + ids.length);
    }
    if (site === 'tieba') {
      const id = (location.pathname.match(/\/p\/(\d+)/) || [])[1];
      if (!id) return fail('请分享贴吧 /p/ 开头的具体帖子链接，暂不支持贴吧首页或整吧下载');
      let body = null;
      for (const post of document.querySelectorAll('.l_post[data-field]')) {
        let data;
        try { data = JSON.parse(post.getAttribute('data-field')); } catch (_) { continue; }
        if (data.content && Number(data.content.post_no) === 1) {
          body = post.querySelector('.d_post_content'); break;
        }
      }
      if (!body) return /验证|安全|登录|不存在|已删除/.test(document.title) ? fail('贴吧要求登录或验证，或主帖已不可访问，请打开来源网页确认') : pending();
      const images = [...body.querySelectorAll('img.BDE_Image')].map(i => http(i.getAttribute('data-original') || i.getAttribute('data-src') || i.getAttribute('src')));
      const videos = [...body.querySelectorAll('video')].map(v => http(v.currentSrc || v.src || (v.querySelector('source') || {}).src));
      if (body.querySelector('iframe,embed,object') || body.querySelector('.video_src') && !videos.length)
        return fail('贴吧主帖含暂不能直接识别的视频，请打开来源网页重新识别，未用封面代替');
      const title = (document.querySelector('.core_title_txt') || {}).textContent || document.title;
      return pack(images, videos, String(title || '贴吧') + ' · 主帖正文', images.length + videos.length);
    }
    if (site === 'xhs') {
      const match = location.pathname.match(/\/(?:explore|discovery\/item)\/([a-f\d]+)/i);
      if (!match) return /\/404|\/login/.test(location.pathname)
        ? fail('小红书页面不可访问，请使用带完整参数的分享链接，必要时在网页中登录') : pending();
      const id = match[1];
      const state = window.__INITIAL_STATE__;
      const detail = state && state.note && state.note.noteDetailMap && state.note.noteDetailMap[id];
      const note = detail && detail.note;
      if (!note || (note.noteId && String(note.noteId) !== id)) return pending();
      if (note.type === 'video' || (note.video && note.video.media)) {
        const stream = note.video && note.video.media && note.video.media.stream;
        const formats = Object.values(stream || {}).flat().filter(v => v && http(v.masterUrl));
        // Prefer AVC for the local player, then the best bitrate within it.
        formats.sort((a, b) => (Number(/264|avc/i.test(b.videoCodec || '')) - Number(/264|avc/i.test(a.videoCodec || '')))
          || ((b.avgBitrate || b.videoBitrate || 0) - (a.avgBitrate || a.videoBitrate || 0)));
        const url = formats.length ? http(formats[0].masterUrl) : '';
        return url ? pack([], [url], note.title || note.desc, 1)
          : fail('该小红书视频暂无可直接下载的地址，请登录后重试；未将封面当作视频保存');
      }
      if (!Array.isArray(note.imageList)) return pending();
      const images = note.imageList.map(image => {
        const infos = image.infoList || [];
        const full = infos.find(i => i.imageScene === 'WB_DFT');
        return http((full && full.url) || image.urlDefault || image.urlPre);
      });
      return pack(images, [], note.title || note.desc, note.imageList.length);
    }
    if (site === 'skland') {
      const id = new URL(location.href).searchParams.get('id');
      if (!/\/(?:article|detail)\/?$/.test(location.pathname) || !id || !/^\d+$/.test(id)) return fail('请分享森空岛的具体帖子链接；合集页面暂不支持整批展开');
      const key = 'skland:' + id;
      let cache = window.__vdCommunity;
      if (!cache || cache.key !== key) {
        cache = window.__vdCommunity = {key, pending: true};
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 15000);
        fetch('https://zonai.skland.com/h5/v1/item/list?ids=' + encodeURIComponent(id), {signal: controller.signal})
          .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
          .then(data => { cache.data = data; })
          .catch(() => { cache.error = '森空岛正文加载失败，请在来源网页登录或稍后重试'; })
          .finally(() => { clearTimeout(timer); cache.pending = false; });
        return pending();
      }
      if (cache.pending) return pending();
      if (cache.error) return fail(cache.error);
      const data = cache.data;
      if (!data || data.code !== 0) return fail('森空岛正文不可访问，请在来源网页登录并确认帖子仍可查看');
      const entry = (data.data && data.data.list || []).find(e => e.item && String(e.item.id) === id);
      if (!entry) return fail('未找到该森空岛帖子，可能已删除或没有查看权限');
      const item = entry.item;
      const imgs = item.imageListSlice || [];
      const vids = item.videoListSlice || [];
      const images = imgs.map(i => http(i.url));
      if ((item.bvSlice || []).length) return fail('正文包含 B站嵌入视频，请使用该视频的 B站链接下载，未将封面当作视频');
      const videos = vids.map(v => {
        const resolutions = (v.resolutions || []).filter(r => http(r.playURL));
        resolutions.sort((a, b) => ((b.width || 0) * (b.height || 0) - (a.width || 0) * (a.height || 0)) || ((b.size || 0) - (a.size || 0)));
        return http(resolutions.length ? resolutions[0].playURL : (v.url || v.playUrl || v.videoUrl));
      });
      // Every source entry must resolve. A video ID alone is not a media URL.
      return pack(images, videos, item.title || (item.textSlice || []).map(t => t.c || '').join(''), imgs.length + vids.length);
    }
    return fail('该平台尚未配置正文解析');
  } catch (_) { return fail('正文数据格式发生变化，暂未下载，请更新或在网页中重新识别'); }
}
