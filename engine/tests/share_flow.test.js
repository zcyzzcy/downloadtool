const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const start = html.indexOf('let sharedDraining = false;');
const end = html.indexOf('\nif (IN_APP) {', start);
assert.ok(start >= 0 && end > start);
function source(name) {
  const start = html.indexOf('function '+name+'(');
  return html.slice(start,html.indexOf('\n}',start)+2);
}
async function main() {
  const queue = ['Cold share https://example.test/a'], submitted = [];
  const input = {value:''};
  let release;
  const context = vm.createContext({
    IN_APP:true,LOCAL_ENGINE:false,window:{},document:{getElementById:()=>input},
    bridgeOk:()=>true,showView(){},toast(){},
    AndroidBridge:{
      getShared:()=>queue[0]||'',queueSharedText:text=>queue.push(text),
      clearSharedText(text){assert.equal(queue[0],text);queue.shift();},
    },
    async submit(){submitted.push(input.value);if(release)await new Promise(resolve=>release.resolve=resolve);input.value='';},
  });
  vm.runInContext(source('extractLinks')+'\n'+html.slice(start,end),context);
  await context.window.receiveShared();assert.equal(submitted.length,0);assert.equal(queue.length,1);
  context.LOCAL_ENGINE=true;await context.window.receiveShared();assert.equal(submitted.length,1);assert.equal(queue.length,0);
  context.LOCAL_ENGINE=false;context.window.onShared('magnet:?xt=urn:btih:1234');assert.equal(queue.length,1);
  context.LOCAL_ENGINE=true;await context.window.receiveShared();assert.equal(submitted[1],'magnet:?xt=urn:btih:1234');
  release={};context.window.onShared('https://example.test/first');
  context.window.onShared('https://example.test/second');assert.equal(submitted.length,3);
  const firstRelease=release.resolve;release=null;firstRelease();
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(queue.length,0);assert.deepEqual(submitted.slice(-2),['https://example.test/first','https://example.test/second']);
  context.window.onShared('plain text');assert.equal(queue.length,0);assert.equal(input.value,'plain text');
  console.log('Cold-share readiness, automatic magnet intake, overlapping shares and plain-text preservation passed');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
