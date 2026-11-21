const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function source(name) {
  const start = html.indexOf('function ' + name + '(');
  assert.ok(start >= 0);
  return html.slice(start, html.indexOf('\n}', start) + 2);
}
const nodes = new Map();
for (const id of ['lib-grid', 'lib-empty', 'lib-n', 'lib-q']) nodes.set(id, {style:{}, innerHTML:'', textContent:'', value:'',insertAdjacentHTML(position,html){this.innerHTML+=html;}});
const requests = [], timers = new Map();
let timerId = 0;
const context = vm.createContext({
  document:{getElementById:id=>nodes.get(id)}, window:{}, curView:'lib', manage:false, selSet:new Set(),
  AndroidBridge:{localFilesAsync:request=>requests.push(request),localThumb(){throw Error('Eager thumbnail work');}},
  bridgeOk:()=>true, updateStorageWarn(){},toast(){},
  collectionMembership:()=>new Map(),libGrouped:items=>items.map(item=>({type:'file',item})),cleanTitle:s=>s,esc:s=>String(s),
  thumbBox:()=>'<img loading="lazy">',fmtDate:()=>'',
  setTimeout(fn){timers.set(++timerId,fn);return timerId;},clearTimeout(id){timers.delete(id);},
});
const globals = 'let libItems=[],libShown=[],libLoading=false,libRequest=0,libRequestTimer=null,libVisibleCount=60,libType="all",libSort="newest";\n';
const callbackStart = html.indexOf('window.onLibraryFiles = function');
const callbackEnd = html.indexOf('\n};', callbackStart)+3;
vm.runInContext(globals+source('loadLib')+'\n'+html.slice(callbackStart,callbackEnd)+'\n'+source('sortedLibraryRows')+'\n'+source('syncLibraryControls')+'\n'+source('renderLib')+'\n'+source('showMoreFiles'),context);
context.loadLib();assert.equal(requests.length,1);context.loadLib();assert.equal(requests.length,1);
context.window.onLibraryFiles('old', '[]');assert.equal(vm.runInContext('libLoading',context),true);
const files=Array.from({length:154},(_,i)=>({name:'file-'+i+'.mp4',sizeMB:1,mtime:1}));
context.window.onLibraryFiles(requests[0],JSON.stringify(files));
assert.equal(vm.runInContext('libLoading',context),false);
assert.equal((nodes.get('lib-grid').innerHTML.match(/data-kind="file"/g)||[]).length,60);
assert.equal(context.window.__libRows.length,154);
context.showMoreFiles();assert.equal((nodes.get('lib-grid').innerHTML.match(/data-kind="file"/g)||[]).length,120);
nodes.get('lib-q').value='file-153';context.renderLib();assert.equal(context.window.__libRows.length,1);
context.loadLib();const timedOut=requests.at(-1);[...timers.values()][0]();
context.window.onLibraryFiles(timedOut,'[]');assert.equal(vm.runInContext('libItems.length',context),154);
context.loadLib();context.window.onLibraryFiles(requests.at(-1),'[]');assert.equal(vm.runInContext('libItems.length',context),0);
console.log('Async library duplicate/stale/timeout guards, 60-row batches and full-library search passed');
