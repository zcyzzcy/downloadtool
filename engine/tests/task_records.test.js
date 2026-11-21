const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(n){const a=html.indexOf('function '+n+'(');assert.ok(a>=0);return html.slice(a,html.indexOf('\n}',a)+2);}
const functions=['parseTaskRecords','loadTaskRecords','saveLocalTasks'].map(source).join('\n');
let native='',browser='[{"id":"legacy","url":"https://example.test","files":["a.mp4"]}]',writes=0,warnings=0;
function session(options={}){
 const c=vm.createContext({IN_APP:true,rememberCollections(){},toast(){warnings++},bridgeOk:()=>true,
  AndroidBridge:{taskRecords:()=>native,saveTaskRecords:value=>{writes++;if(options.failNative)return false;native=value;return true;}},
  localStorage:{getItem:()=>{if(options.failRead)throw Error();return browser;},setItem:(k,v)=>{if(options.failBrowser)throw Error();if(!options.staleBrowser)browser=v;}}
 });
 vm.runInContext(functions+'\nlet localTasks=loadTaskRecords();',c);
 return code=>vm.runInContext(code,c);
}
let run=session();assert.equal(run('localTasks[0].id'),'legacy');run('saveLocalTasks()');assert.equal(writes,1,'legacy history migrates on normal save');
// Simulate native durable deletion followed by immediate process death before browser flush.
run=session({staleBrowser:true});run('localTasks=[];saveLocalTasks()');assert.equal(native,'[]');
run=session();assert.equal(run('localTasks.length'),0,'authoritative empty native list must not resurrect stale browser tasks');
native='[null,7,{}]';run=session();assert.equal(run('localTasks[0].id'),'legacy','nonempty invalid native rows are not an intentional clear');
native='bad';run=session();assert.equal(run('localTasks[0].id'),'legacy','bad native data falls back to legacy browser');
run('saveLocalTasks()');assert.ok(JSON.parse(native).length===1,'normal save repairs native backup');
const previous=writes;run('saveLocalTasks();saveLocalTasks()');assert.equal(writes,previous,'identical snapshots avoid synchronous disk writes');
native='[{"id":"a","url":"https://a","files":"bad"},null,{},7]';run=session();assert.equal(run('localTasks.length'),1);assert.equal(run('localTasks[0].files.length'),0);
run=session({failNative:true});run('localTasks.push({id:"browser-only",url:"https://b"});saveLocalTasks()');assert.ok(browser.includes('browser-only'));assert.ok(warnings>0,'failed durable write is explained');
native='broken';run=session({failRead:true});assert.equal(run('localTasks.length'),0,'both unreadable stores cannot break app startup');
run=session({failNative:true,failBrowser:true});assert.equal(run('saveLocalTasks()'),false,'complete storage failure is observable');
native='';browser='[]';run=session();run('localTasks=Array.from({length:60},(_,i)=>({id:String(i),url:"https://"+i,status:i<3?"downloading":"done"}));saveLocalTasks()');
assert.equal(JSON.parse(native).length,53,'retain every running task plus last 50 rows');
assert.equal(JSON.parse(native)[0].id,'0');assert.equal(JSON.parse(native).at(-1).id,'59');
console.log('Durable history migration, immediate-close deletion, authoritative empty list, corruption fallback, write deduplication, storage failure and active-task retention passed');
