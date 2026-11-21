const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/community_collect.js'), 'utf8');
function make(url, state, response) {
  let calls = 0;
  const u = new URL(url);
  const context = vm.createContext({URL, AbortController, setTimeout, clearTimeout,
    document: {title: 'Post', querySelectorAll() { throw Error('Must not collect the feed'); }},
    location: {href: url, pathname: u.pathname}, window: {__INITIAL_STATE__: state},
    fetch: async target => { calls++; assert.match(target, /\/h5\/v1\/item\/list\?ids=12345$/); return {ok: true, json: async () => response}; },
  });
  vm.runInContext(script, context);
  return {context, calls: () => calls, read: site => JSON.parse(vm.runInContext(`vdCollectCommunity('${site}')`, context))};
}
const id = 'abcdef1234567890';
const images = Array.from({length: 70}, (_, i) => ({urlDefault: `https://cdn.test/${i}.webp?signature=keep`}));
let test = make('https://www.xiaohongshu.com/explore/' + id, {note: {noteDetailMap: {
  recommendation: {note: {imageList: [{urlDefault: 'https://cdn.test/wrong.jpg'}]}},
  [id]: {note: {noteId: id, type: 'normal', imageList: images, title: 'Selected post'}},
}}});
let out = test.read('xhs');
assert.equal(out.media.images.length, 70);
assert.ok(out.media.images.every(u => u.endsWith('?signature=keep')));
assert.equal(out.media.complete, true);
assert.equal(out.media.desc, 'Selected post');
test.context.window.__INITIAL_STATE__.note.noteDetailMap[id].note.imageList.push({});
assert.match(test.read('xhs').error, /不完整/);
test.context.window.__INITIAL_STATE__.note.noteDetailMap[id].note = {type: 'video', imageList: images};
assert.equal(test.read('xhs').media, null);
assert.match(test.read('xhs').error, /未将封面/);
test.context.window.__INITIAL_STATE__.note.noteDetailMap[id].note.video = {media: {stream: {
  h264: [{masterUrl: 'https://cdn.test/video.mp4', avgBitrate: 100, videoCodec: 'h264'}],
  h265: [{masterUrl: 'https://cdn.test/hevc.mp4', avgBitrate: 200, videoCodec: 'h265'}],
}}};
assert.deepEqual(test.read('xhs').media.videos, ['https://cdn.test/video.mp4']);
assert.deepEqual(test.read('xhs').media.images, []);
assert.match(make('https://www.xiaohongshu.com/404/sec_bad').read('xhs').error, /完整参数/);

(async () => {
  const item = {id: '12345', title: 'Selected island post', imageListSlice: images.slice(0, 3).map(i => ({url: i.urlDefault})), videoListSlice: []};
  test = make('https://m.skland.com/article?id=12345', null, {code: 0, data: {list: [{item}, {item: {id: '999', imageListSlice: [{url: 'https://cdn.test/wrong.jpg'}]}}]}});
  assert.equal(test.read('skland').media, null);
  assert.equal(test.read('skland').media, null);
  await new Promise(resolve => setImmediate(resolve));
  out = test.read('skland');
  assert.equal(test.calls(), 1);
  assert.equal(out.media.images.length, 3);
  assert.equal(out.media.desc, item.title);
  item.videoListSlice = [{id: 'opaque-video-id'}];
  assert.match(test.read('skland').error, /不完整/);
  item.videoListSlice = [{resolutions: [{width: 640, height: 360, playURL: 'https://cdn.test/small.mp4'}, {width: 1920, height: 1080, playURL: 'https://cdn.test/film.mp4'}]}];
  assert.deepEqual(test.read('skland').media.videos, ['https://cdn.test/film.mp4']);
  assert.match(make('https://m.skland.com/collection?id=12345').read('other').error, /尚未配置/);
  assert.match(make('https://m.skland.com/collection').read('skland').error, /具体帖子/);
  test = make('https://m.skland.com/article?id=12345', null, {code: 10001, message: 'login'});
  test.read('skland'); await new Promise(resolve => setImmediate(resolve));
  assert.match(test.read('skland').error, /登录/);
  console.log('Exact-post isolation, full image lists, signed URLs, video/cover separation, single API request and incomplete/auth failure checks passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
