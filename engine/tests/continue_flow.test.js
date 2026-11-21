const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(n){const a=html.indexOf('function '+n+'(');assert.ok(a>=0);return html.slice(a,html.indexOf('\n}',a)+2);}
let progress={'old.mp4':10,'new.mp4':25},played=[],requests=[],messages=[];
const store=new Map(),timers=new Map();let timer=0;
const main={disabled:false},card={hidden:true,dataset:{},innerHTML:'',setAttribute(k,v){this[k]=v},querySelector:()=>main};
const ctx=vm.createContext({curView:'home',hManage:false,window:{},document:{getElementById:id=>id==='continue-watching'?card:null},
 isVideoName:n=>/\.mp4$/i.test(n),readProgress:()=>progress,localTasks:[],collectionCatalog:()=>[],libItems:[],
 kvGet:(k,d)=>store.get(k)||d,localStorage:{setItem:(k,v)=>store.set(k,v)},esc:s=>String(s),fmtDur:s=>s+'s',
 bridgeOk:n=>n==='localFilesAsync',AndroidBridge:{localFilesAsync:id=>requests.push(id)},openPlayerSet:(...args)=>played.push(args),toast:s=>messages.push(s),
 setTimeout(fn){timers.set(++timer,fn);return timer},clearTimeout(id){timers.delete(id)}
});
vm.runInContext("let continueFiles=null,continueRequest='',continuePending='',continueTimer=0,continueGeneration=0;\n"+['continueCandidate','invalidateContinueFiles','scanContinueFiles','onContinueFiles','continueHiddenToken','renderContinueWatching','resumeContinue','dismissContinue','taskErrorSummary','taskErrorHint'].map(source).join('\n'),ctx);
const run=s=>vm.runInContext(s,ctx);
ctx.files=[{name:'old.mp4'},{name:'new.mp4'},{name:'photo.jpg'}];ctx.progress=progress;
assert.equal(run('continueCandidate(progress,files,[],[]).name'),'new.mp4');
assert.equal(run("continueCandidate(progress,[{name:'old.mp4'}],[],[]).name"),'old.mp4');
assert.equal(run("continueCandidate({'new.mp4':25},files,[],[{title:'Series',items:[{name:'new.mp4',title:'Episode 2',dur:60}]}]).title"),'Episode 2');
assert.equal(run("continueCandidate({'new.mp4':57},files,[],[{title:'Series',items:[{name:'new.mp4',dur:60}]}])"),null);
assert.equal(run("continueCandidate({'new.mp4':'25','photo.jpg':10,'old.mp4':Infinity},files,[],[])"),null);
assert.equal(run('continueCandidate([],files,[],[])'),null);
ctx.renderContinueWatching();ctx.renderContinueWatching();assert.equal(requests.length,1,'Home refreshes must share one scan');
ctx.onContinueFiles('stale',JSON.stringify(ctx.files));assert.equal(card.hidden,true);
ctx.onContinueFiles(requests.at(-1),JSON.stringify(ctx.files));assert.equal(card.hidden,false);assert.ok(card.innerHTML.includes('25s'));
ctx.resumeContinue('new.mp4');assert.equal(main.disabled,true);ctx.resumeContinue('new.mp4');assert.equal(requests.length,2);
ctx.curView='lib';ctx.onContinueFiles(requests.at(-1),JSON.stringify(ctx.files));assert.equal(played.length,0,'Late response cannot interrupt another page');
ctx.curView='home';ctx.renderContinueWatching();ctx.resumeContinue('new.mp4');ctx.onContinueFiles(requests.at(-1),JSON.stringify([{name:'old.mp4'}]));assert.equal(played.length,0);assert.ok(messages.at(-1).includes('移走'));
ctx.invalidateContinueFiles();ctx.renderContinueWatching();ctx.onContinueFiles(requests.at(-1),JSON.stringify(ctx.files));ctx.resumeContinue('new.mp4');ctx.onContinueFiles(requests.at(-1),JSON.stringify(ctx.files));assert.equal(played[0][0][0],'new.mp4');
ctx.renderContinueWatching();ctx.resumeContinue('new.mp4');const dismissedRequest=requests.at(-1);ctx.dismissContinue();assert.equal(card.hidden,true);ctx.onContinueFiles(dismissedRequest,JSON.stringify(ctx.files));assert.equal(played.length,1,'Dismiss cancels a pending open');assert.equal(progress['new.mp4'],25,'Dismiss keeps saved progress');
progress={'old.mp4':10,'new.mp4':26};ctx.renderContinueWatching();assert.equal(card.hidden,false,'Watching again makes the card eligible again');
ctx.resumeContinue('new.mp4');const callback=[...timers.values()][0];callback();assert.ok(messages.at(-1).includes('超时'));assert.equal(card.hidden,true);
ctx.hManage=true;ctx.invalidateContinueFiles();ctx.renderContinueWatching();assert.equal(card.hidden,true);
assert.ok(ctx.taskErrorSummary('Requested format is not available').includes('格式不可用'));
assert.ok(ctx.taskErrorSummary('HTTP Error 403: Forbidden').includes('可能需要先登录'));
assert.ok(ctx.taskErrorSummary('Connection timed out').includes('连接暂时失败'));
assert.ok(ctx.taskErrorSummary('Unsupported URL').includes('网页下载'));
assert.ok(ctx.taskErrorSummary('unknown decoder failure').includes('展开'));
assert.ok(ctx.taskErrorHint('该作品尚未解锁').includes('已购买'));
console.log('Resume eligibility, legacy progress, durable metadata, scan sharing, stale responses, missing files, dismissal, timeout and Chinese errors passed');
// Check the production error card actions, retaining raw diagnostics for inspection.
const nodes=new Map();for(const id of ['list','home-ops','hmg-btn','home-done-n','task-filters','task-more'])nodes.set(id,{style:{},classList:{toggle(){}},innerHTML:'',textContent:''});
const e=vm.createContext({document:{getElementById:id=>nodes.get(id)},window:{},curView:'home',hManage:false,homeSel:new Set(),IN_APP:true,
 renderContinueWatching(){},healDoneTasks(){},schedulePoll(){},probeTaskDur(){},STATUS:{error:'失败'},esc:s=>String(s)});
vm.runInContext("let homeTaskFilter='error',homeTaskLimit=12,lastTaskRenderKey='',localTasks=[{id:'locked',status:'error',title:'Locked',url:'https://zhuliwa.com/post/demo',error:'ERROR: [zhuliwa] abc: 该作品尚未解锁，请登录已购买账号',files:[]}];\n"+['taskErrorSummary','taskErrorHint','homeTaskRows','renderLocalTasks'].map(source).join('\n'),e);
e.renderLocalTasks();let markup=nodes.get('list').innerHTML;assert.ok(markup.includes('打开来源页面'));assert.ok(!markup.includes('appCollect('));assert.ok(markup.includes('ERROR: [zhuliwa] abc:'),'Original diagnostics must remain available');
vm.runInContext("localTasks[0].url='https://www.bilibili.com/video/test';localTasks[0].error='HTTP Error 403: Forbidden';",e);e.renderLocalTasks();markup=nodes.get('list').innerHTML;assert.ok(markup.includes('startLogin('));assert.ok(!markup.includes('appCollect('));
console.log('Locked content offers source access; login failures prioritize login; raw diagnostics stay intact');

ctx.bridgeOk=n=>n==='kvGet';ctx.AndroidBridge.kvGet=()=> 'native-token';assert.equal(ctx.continueHiddenToken(),'native-token');ctx.AndroidBridge.kvGet=()=>'';assert.equal(ctx.continueHiddenToken(),store.get('vd_continue_hidden'));console.log('Hidden recommendation preference supports native persistence and legacy fallback');
