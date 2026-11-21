const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const start = html.indexOf('function submitCollection(');
const end = html.indexOf('\n}', start);
assert.ok(start >= 0 && end > start);
let serial = 0, rejectAfter = Infinity;
const context = {
  localTaskById: id => vm.runInContext('localTasks', context).find(t => t.id === id),
  isDoneUrl: () => false,
  localSubmitUrl: (url, quality, referer, userAgent, outTitle, force) => {
    if (serial >= rejectAfter) return null;
    const id = 'item-' + ++serial;
    vm.runInContext('localTasks', context).push({ id, url, quality, referer, userAgent, outTitle, force, title: outTitle, status: 'parsing' });
    return id;
  },
  saveLocalTasks() {}, renderLocalTasks() {}, toast() {},
};
vm.createContext(context);
vm.runInContext('let localTasks = [];\n' + html.slice(start, end + 2), context);
const parent = () => ({ id: 'parent', url: 'https://b23.tv/collection', quality: '720', status: 'parsing' });
const entries = Array.from({ length: 22 }, (_, i) => ({ url: 'https://www.bilibili.com/video/BV1test' + String(i).padStart(5, '0'), title: 'Episode ' + (i + 1) }));
let task = parent();
vm.runInContext('localTasks', context).push(task);
context.submitCollection(task, 'Collection', JSON.stringify(entries));
let tasks = vm.runInContext('localTasks', context);
assert.equal(tasks.length, 22);
assert.equal(new Set(tasks.map(t => t.id)).size, 22);
assert.equal(tasks.at(-1).collectionIndex, 22);
assert.ok(tasks.every(t => t.collectionTotal === 22 && t.referer === 'https://www.bilibili.com/' && t.force === 0));
task = parent();
tasks.push(task);
context.submitCollection(task, 'Collection', JSON.stringify(entries));
assert.equal(vm.runInContext('localTasks.length', context), 22);

vm.runInContext('localTasks = []', context);
serial = 0; rejectAfter = 10;
task = parent();
vm.runInContext('localTasks', context).push(task);
context.submitCollection(task, 'Collection', JSON.stringify(entries));
assert.equal(task.status, 'error');
assert.match(task.error, /12/);
assert.equal(vm.runInContext('localTasks.length', context), 11);
rejectAfter = Infinity;
context.submitCollection(task, 'Collection', JSON.stringify(entries));
assert.equal(vm.runInContext('localTasks.length', context), 22);
assert.equal(serial, 22);

vm.runInContext('localTasks = []', context);
task = parent();
vm.runInContext('localTasks', context).push(task);
const unavailable = entries.map((entry, i) => i === 3 ? { ...entry, error: 'Video unavailable' } : entry);
context.submitCollection(task, 'Collection', JSON.stringify(unavailable));
assert.equal(vm.runInContext("localTasks.filter(t => t.status === 'error').length", context), 2);
assert.throws(() => context.submitCollection(task, 'Bad', '[{"url":"file:///private"}]'), /无效/);
console.log('22-item collection expansion, deduplication, partial retry and unavailable-entry checks passed');

vm.runInContext('localTasks = []', context);
task = parent();
vm.runInContext('localTasks', context).push(task);
context.submitCollection(task, 'Mixed', JSON.stringify([{url:'https://www.bilibili.com/medialist/detail/ml99#vd-unavailable-12-5',title:'Non-video',error:'Not a video'}, entries[0]]));
assert.equal(vm.runInContext("localTasks.filter(t => t.collectionEntryError).length", context), 1);
assert.equal(vm.runInContext("localTasks.filter(t => t.url === 'https://www.bilibili.com/video/BV1test00000').length", context), 1);
assert.throws(() => context.submitCollection(task, 'Bad', '[{"url":"file:///private","error":"x"}]'), /无效/);
