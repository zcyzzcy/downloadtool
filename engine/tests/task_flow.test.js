const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');

const html = fs.readFileSync(process.argv[2] || path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function source(name) {
  const start = html.indexOf('function ' + name + '(');
  const end = html.indexOf('\n}', start);
  assert.ok(start >= 0 && end > start, 'Missing function ' + name);
  return html.slice(start, end + 2);
}

let saved;
let browserArgs;
let accepted = 'new-task';
const context = {
  LOCAL_ENGINE: true, IN_APP: true, rememberCollections(){},invalidateContinueFiles(){},
  localStorage: { setItem: (key, value) => { saved = JSON.parse(value); } },
  AndroidBridge: {
    localSubmit: () => accepted,
    localBrowserSubmit: (...args) => { browserArgs = args; return accepted; },
    localMagnet: () => accepted,
  },
  bridgeOk: () => true,
  localCookiesText: () => '',
  renderLocalTasks() {}, updateLocalProgress: () => true, toast() {}, esc: text => String(text),
  isDoneUrl: () => false,
  kvGet: (key, fallback) => fallback,
  galleryKindOf: () => null,
};
vm.createContext(context);
vm.runInContext('let localTasks = [];\n' + [
  'acknowledgeLocalState', 'saveLocalTasks', 'localTaskById', 'localRetry', 'localSubmitUrl', 'submitDouyinLocal', 'submitOne',
].map(source).join('\n'), context);
const run = code => vm.runInContext(code, context);

run("localTasks = Array.from({length: 55}, (_, i) => ({id: String(i), status: 'done'})); saveLocalTasks();");
assert.equal(saved.length, 50);
assert.equal(saved[0].id, '5');
assert.equal(saved.at(-1).id, '54');
run("localTasks[0].status = 'downloading'; saveLocalTasks();");
assert.equal(saved.length, 51);
assert.equal(saved[0].id, '0');

run("localTasks = [{id:'old', url:'https://example.test/file.mp4', status:'error', quality:'720'}];");
accepted = null;
run("localRetry('old');");
assert.equal(run('localTasks.length'), 1);
assert.equal(run('localTasks[0].id'), 'old');
assert.match(run('localTasks[0].error'), /重试失败/);
context.AndroidBridge.localSubmit = () => { throw new Error('bridge failed'); };
run("localRetry('old');");
assert.equal(run('localTasks[0].id'), 'old');
context.AndroidBridge.localSubmit = () => accepted;

accepted = 'browser-task';
run("localTasks = []; localSubmitUrl('https://cdn.test/a.mp4', '720', 'https://example.test/watch', 'test-agent', '测试标题', 1);");
assert.equal(run('localTasks[0].referer'), 'https://example.test/watch');
assert.equal(run('localTasks[0].userAgent'), 'test-agent');
assert.equal(run('localTasks[0].title'), '测试标题');
run("localTasks[0].status = 'error';");
accepted = 'retried-task';
run("localRetry('browser-task');");
assert.equal(run('localTasks.length'), 1);
assert.equal(run('localTasks[0].id'), 'retried-task');
assert.equal(JSON.stringify(browserArgs), JSON.stringify([
  'https://cdn.test/a.mp4', '720', '', 'https://example.test/watch', 'test-agent', '测试标题', 1,
]));

accepted = null;
assert.equal(run("submitOne('https://www.douyin.com/video/123', true)"), 'busy');
accepted = 'douyin-task';
assert.equal(run("submitOne('https://www.douyin.com/video/123', true)"), 'ok');
run("localTasks = [{id:'magnet-old',url:'magnet:?xt=urn:btih:test',status:'error'}];");
accepted = null;
run("localRetry('magnet-old');");
assert.equal(run('localTasks[0].id'), 'magnet-old');
accepted = 'magnet-new';
run("localRetry('magnet-old');");
assert.equal(run('localTasks.length'), 1);
assert.equal(run('localTasks[0].id'), 'magnet-new');

const stateStart = html.indexOf('window.onLocalState = function');
const stateEnd = html.indexOf('\n};', stateStart);
Object.assign(context, {
  window: {}, curView: 'home',
  document: { hidden: false }, navigator: {},
  cleanTitle: text => text || '',
  rememberDone() {},
});
context.AndroidBridge.localFiles = () => JSON.stringify([
  { name: 'a.mp4', sizeMB: 1.2 }, { name: 'b.mp4', sizeMB: 1.3 }, { name: 'other.mp4', sizeMB: 10 },
]);
vm.runInContext(html.slice(stateStart, stateEnd + 3), context);
run("localTasks = [{id:'completed',url:'https://example.test/gallery',files:[],status:'downloading'}];");
context.window.onLocalState('completed', 'done', 'Title', null, '["a.mp4","b.mp4"]');
assert.equal(run('localTasks[0].sizeMB'), 2.5);
run("localTasks = [{id:'partial',url:'https://www.skland.com/article?id=12345',files:[],status:'downloading'}];");
context.fallbackKindOf = () => { throw new Error('Partial success must not re-collect'); };
context.window.onLocalState('partial', 'error', 'Title', '仅保存成功 1/2 项', '["a.mp4"]');
assert.equal(run('localTasks[0].status'), 'error');
assert.equal(run('localTasks[0].partial'), true);
assert.equal(run('localTasks[0].files[0]'), 'a.mp4');
assert.equal(run('localTasks[0].filename'), 'a.mp4');


const damagedContext = vm.createContext({
  IN_APP:false,
  localStorage: {
    getItem: () => JSON.stringify([null, 7, {}, { id: 'valid', url: 'https://example.test', files: 'bad' }]),
  },
});
vm.runInContext(['parseTaskRecords','loadTaskRecords'].map(source).join('\n') + '\nlet localTasks = loadTaskRecords();', damagedContext);
assert.equal(vm.runInContext('localTasks.length', damagedContext), 1);
assert.equal(vm.runInContext('localTasks[0].files.length', damagedContext), 0);
context.updateStorageWarn = () => {};
context.AndroidBridge.localActiveTasks = () => '["running"]';
run("localTasks = [{id:'running', url:'https://example.test/active', status:'downloading'}, {id:'interrupted',url:'https://example.test/old',status:'parsing'}];");
context.window.onLocalState(null, 'ready', null, '/storage/test', null);
assert.equal(run('localTasks[0].status'), 'downloading');
assert.equal(run('localTasks[1].status'), 'error');
const progressStart = html.indexOf('window.onLocalProgress = function');
const progressEnd = html.indexOf('\n};', progressStart);
vm.runInContext(['progressBytes', 'progressSize', 'progLine'].map(source).join('\n'), context);
vm.runInContext(html.slice(progressStart, progressEnd + 3), context);
context.window.onLocalProgress('interrupted', 25, '25% of 1MiB at 1MiB/s');
assert.equal(run('localTasks[1].status'), 'downloading');
assert.equal(run('localTasks[1].error'), '');
console.log('Task persistence, retry and browser metadata checks passed');

run("localTasks = [{id:'live',status:'parsing'}, {id:'cancelled',status:'error',error:'任务被取消'}];");
context.window.onLocalProgress('live', 0, JSON.stringify({status:'downloading', downloaded_bytes:1024,total_bytes:10485760,speed:2048}));
assert.equal(run('localTasks[0].status'), 'downloading');
assert.match(run('progLine(localTasks[0])'), /0%.*速度 2.0 KB\/s.*已下载 1.0 KB.*总大小 10.0 MB/);
context.window.onLocalProgress('live', 25, '25% of 100MiB at 2MiB/s');
assert.equal(run('localTasks[0].downloadedBytes'), 25 * 1048576);
assert.equal(run('localTasks[0].totalBytes'), 100 * 1048576);
context.window.onLocalProgress('live', 20, '[#abcdef 2MiB/10MiB(20%) CN:4 DL:512KiB]');
assert.equal(run('localTasks[0].speedBytes'), 512 * 1024);
context.window.onLocalProgress('live', 20, JSON.stringify({status:'downloading',downloaded_bytes:100,total_bytes:0,speed:0}));
assert.match(run('progLine(localTasks[0])'), /总大小待获取/);
run('localTasks[0].lastProgressAt = Date.now() - 6000; localTasks[0].speedBytes = 1048576;');
assert.match(run('progLine(localTasks[0])'), /速度 0 B\/s/);
context.window.onLocalProgress('live', 99, JSON.stringify({status:'finished',downloaded_bytes:100,total_bytes:100,speed:0}));
assert.equal(run('localTasks[0].status'), 'merging');
context.window.onLocalProgress('live', 0, JSON.stringify({status:'downloading',downloaded_bytes:0,total_bytes:100,speed:0}));
assert.equal(run('localTasks[0].status'), 'downloading');
context.window.onLocalProgress('cancelled', 10, '10% of 1MiB at 1MiB/s');
assert.equal(run('localTasks[1].status'), 'error');
console.log('Live size/speed, zero progress, track transitions and late cancellation checks passed');

context.window.onLocalProgress('live', 10, JSON.stringify({status:'downloading',downloaded_bytes:10,total_bytes:100,speed:0,note:'正在进行第 2/3 次尝试'}));
assert.match(run('progLine(localTasks[0])'), /正在进行第 2\/3 次尝试/);
context.window.onLocalProgress('live', 20, JSON.stringify({status:'downloading',downloaded_bytes:20,total_bytes:100,speed:10}));
assert.equal(run('localTasks[0].progressNote'), '');

let acknowledged = [];
context.window.__engineReadySeen = false;
context.AndroidBridge.localTaskStates = () => JSON.stringify([['lost-completion','done','Recovered',null,'["a.mp4"]']]);
context.AndroidBridge.localAcknowledgeTask = id => acknowledged.push(id);
run("localTasks = [{id:'lost-completion',url:'https://example.test/lost',status:'downloading'}];");
context.window.onLocalState(null, 'ready', null, '/storage/test', null);
assert.equal(run('localTasks[0].status'), 'done');
assert.ok(acknowledged.includes('lost-completion'));
run("localTasks = [{id:'unsupported',url:'https://www.bilibili.com/medialist/detail/ml99#vd-unavailable-12-5',status:'error',collectionEntryError:'不是视频',collectionUrl:'https://space.bilibili.com/1/favlist?fid=99'}];");
accepted = 'refresh-favorite';
context.AndroidBridge.localSubmit = () => accepted;
run("localRetry('unsupported');");
assert.equal(run('localTasks[0].url'), 'https://space.bilibili.com/1/favlist?fid=99');
console.log('Recovered completion acknowledgement and unsupported-item retry checks passed');

run("localTasks = [{id:'old-unsupported',url:'https://www.bilibili.com/video/av5',status:'error',error:'收藏条目不是视频，无法下载',collectionUrl:'https://space.bilibili.com/1/favlist?fid=99'}];");
run("localRetry('old-unsupported');");
assert.equal(run('localTasks[0].url'), 'https://space.bilibili.com/1/favlist?fid=99');
