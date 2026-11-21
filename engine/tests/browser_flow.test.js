const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const start = html.indexOf('window.onBrowserDownload = function');
const end = html.indexOf('// 底部「浏览器」导航', start);
assert.ok(start >= 0 && end > start, 'Browser download handler boundaries must exist');
let messages = [], submissions = 0;
const context = {
  window: {}, LOCAL_ENGINE: false, localTasks: [],
  bridgeOk: () => true,
  AndroidBridge: { browserDownloadResult: m => messages.push(m), localWarmDouyin() {} },
  localStorage: { getItem: () => 'auto' },
  kvGet: (key, fallback) => fallback,
  localSubmitUrl: url => { const id = 'task-' + ++submissions; context.localTasks.push({ id, url, status: 'parsing' }); return id; },
  saveLocalTasks() {}, renderLocalTasks() {}, toast: m => messages.push(m),
};
vm.createContext(context);
vm.runInContext(html.slice(start, end), context);
const download = context.window.onBrowserDownload;
download('https://example.test/video', 'Video');
assert.equal(submissions, 0);
assert.match(messages.pop(), /没就绪/);
context.LOCAL_ENGINE = true;
download('https://example.test/video', 'Video');
assert.equal(submissions, 1);
assert.equal(context.localTasks[0].title, 'Video');
assert.match(messages.pop(), /已加入/);
download('https://example.test/video', 'Video');
assert.equal(submissions, 1);
assert.match(messages.pop(), /已在/);
download('file:///data/private', 'Invalid');
assert.equal(submissions, 1);
assert.match(messages.pop(), /不支持/);
context.localSubmitUrl = () => null;
download('https://example.test/rejected', 'Rejected');
assert.match(messages.pop(), /未能创建/);
assert.equal(context.localTasks.length, 1);
context.localSubmitUrl = () => { throw new Error('failed'); };
download('https://example.test/another', 'Fail');
assert.match(messages.pop(), /提交失败/);
console.log('Browser download flow checks passed');
