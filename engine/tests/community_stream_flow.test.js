const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');
function source(name){const i=html.indexOf('function '+name+'(');return html.slice(i,html.indexOf('\n}',i)+2);}
let serial=0,rejectAt=Infinity,remembered=[],requests=[];
const c=vm.createContext({window:{},IN_APP:true,curView:'home',document:{hidden:false},navigator:{},
  cleanTitle:x=>x||'',saveLocalTasks(){},renderLocalTasks(){},acknowledgeLocalState(){},
  rememberDone:(...args)=>remembered.push(args),
  AndroidBridge:{localRescue:json=>{const m=JSON.parse(json);assert.equal(m.images.length,2);return 'images-'+ ++serial;},localFiles:()=> '[]'},
  localSubmitUrl:(...args)=>{requests.push(args);if(requests.length===rejectAt)return null;const id='video-'+ ++serial;
    vm.runInContext('localTasks',c).push({id,url:args[0],title:args[4],status:'parsing',files:[]});return id;},
});
vm.runInContext('let localTasks=[];\n'+['localTaskById','completeMediaCollection','submitCollectedStreams'].map(source).join('\n'),c);
const start=html.indexOf('window.onLocalState = function');
vm.runInContext(html.slice(start,html.indexOf('\n};',start)+3),c);
const ctx={url:'https://www.taptap.cn/moment/12345'};
const m={images:['https://cdn.test/a.jpg','https://cdn.test/b.jpg'],videos:[],streams:['https://cdn.test/one.m3u8?sig=1','https://cdn.test/two.m3u8?sig=2'],desc:'Mixed post',referer:ctx.url,userAgent:'Source UA'};
c.submitCollectedStreams(ctx,m);
let tasks=vm.runInContext('localTasks',c);assert.equal(tasks.length,3);assert.ok(tasks.every(t=>t.mediaCollection&&t.__rescue&&t.collectionTotal===3));
assert.deepEqual(Array.from(tasks,t=>t.collectionIndex),[1,2,3]);
assert.ok(requests.every(a=>a[2]===ctx.url&&a[3]==='Source UA'&&a[5]===1));assert.notEqual(requests[0][4],requests[1][4]);
c.window.onLocalState(tasks[0].id,'done','Images',null,'["a.jpg","b.jpg"]');assert.equal(remembered.length,0);
c.window.onLocalState(tasks[1].id,'done','Video 1',null,'["one.mp4"]');assert.equal(remembered.length,0);
c.window.onLocalState(tasks[2].id,'error','Video 2','download failed',null);assert.equal(remembered.length,0);
c.window.onLocalState(tasks[2].id,'done','Video 2',null,'["two.mp4"]');assert.equal(remembered.length,1);
assert.equal(remembered[0][0],ctx.url);assert.deepEqual(Array.from(remembered[0][1]),['a.jpg','b.jpg','one.mp4','two.mp4']);
vm.runInContext('localTasks=[]',c);requests=[];remembered=[];rejectAt=2;c.submitCollectedStreams(ctx,m);
tasks=vm.runInContext('localTasks',c);assert.equal(tasks.length,3);assert.equal(tasks[2].status,'error');
tasks[0].status=tasks[1].status='done';c.completeMediaCollection(tasks[0]);assert.equal(remembered.length,0);
console.log('Mixed galleries/HLS: separate engine jobs, unique names, source headers, grouping and source completion only after every part passed');

// An earlier failed attempt must not block a later complete attempt of the same post.
requests=[];rejectAt=Infinity;remembered=[];c.submitCollectedStreams({...ctx,pseudoId:'new-attempt'},m);
const fresh=vm.runInContext("localTasks.filter(t=>t.mediaRunId==='new-attempt')",c);assert.equal(fresh.length,3);
for(const [i,t] of fresh.entries()){t.status='done';t.files=['new-'+i+'.mp4'];}
c.completeMediaCollection(fresh[2]);assert.equal(remembered.length,1);assert.equal(remembered[0][1].length,3);
let opened;
c.bridgeOk=()=>true;c.AndroidBridge.openBrowse=url=>{opened=url;};
vm.runInContext(source('openFailureSource'),c);c.openFailureSource(fresh[1].id);assert.equal(opened,ctx.url);
console.log('Retry attempts have independent completion accounting and stream failures open the original post');
