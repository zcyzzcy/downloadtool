const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(n){const a=html.indexOf('function '+n+'(');return html.slice(a,html.indexOf('\n}',a)+2);}
const nodes=new Map();let stack=[{v:'home'}],backCalls=0,closed=[];
const context=vm.createContext({window:{},localStorage:{setItem(){}},document:{getElementById:id=>nodes.get(id)||null},
 history:{pushState(s){stack.push(s)},replaceState(s){stack[stack.length-1]=s},back(){backCalls++;if(stack.length>1)stack.pop();context.applyView(stack.at(-1).v)}},
 appConfirmClose(){closed.push('confirm');nodes.delete('app-confirm')},closeLibraryMenu(){closed.push('menu');nodes.delete('library-actions')},
 dpClose(){closed.push('dir');nodes.delete('dirpick')},ivClose(){closed.push('image');nodes.delete('imgview')},memClose(){},gdFs(){},
 closePlayerDetails(){closed.push('episodes');vm.runInContext("playerDetailMode=''",context)},exitManage(){closed.push('manage');vm.runInContext('manage=false',context)}
});
context.applyView=v=>vm.runInContext('curView='+JSON.stringify(v),context);
vm.runInContext("let curView='home',manage=false,hManage=false,mManage=false,gdFsOn=false,playerDetailMode='';\n"+['showView','subView','switchTab','closeTopLayer','appBack'].map(source).join('\n'),context);
const run=s=>vm.runInContext(s,context);
run("switchTab('mine');switchTab('lib');switchTab('mine');");assert.equal(stack.length,1);
run("subView('settings');subView('setqual');");assert.equal(stack.length,3);
run('appBack()');assert.equal(run('curView'),'settings');assert.equal(stack.length,2);
run('appBack()');assert.equal(run('curView'),'mine');assert.equal(stack.length,1);
run('appBack()');assert.equal(run('curView'),'home');assert.equal(run('appBack()'),false);
nodes.set('library-actions',{});assert.equal(run('appBack()'),true);assert.equal(backCalls,2);assert.equal(run('curView'),'home');
run("curView='player';playerDetailMode='collection';manage=true;");
nodes.set('app-confirm',{style:{display:'flex'},classList:{contains:()=>false}});
run('appBack();appBack();appBack();');assert.deepEqual(closed.slice(-3),['confirm','episodes','manage']);
run("curView='home';showView('settings');showView('settings');");assert.equal(stack.length,2,'Repeated page entry does not accumulate history');
assert.ok(!/class="back" onclick="subView/.test(html));
assert.ok(!html.includes('iv: 1'));assert.ok(!html.includes('dp: 1'));
console.log('Tab deduplication, nested back, same-page guard, root overlays and layered dismissal passed');
// Episode search and lazy per-file details must not change playback.
const items=['1 Episode A','2 Episode B'].map(search=>({dataset:{search},hidden:false}));
const empty={hidden:true};const panel={querySelectorAll:()=>items,querySelector:()=>empty};nodes.set('player-detail-panel',panel);
context.videoDetailsOf=name=>({title:name,raw:'different source'});context.esc=s=>s;context.detailsBodyHtml=d=>d.raw;
vm.runInContext(['filterPlayerEpisodes','toggleEpisodeInfo'].map(source).join('\n'),context);
run("filterPlayerEpisodes('2');");assert.equal(items[0].hidden,true);assert.equal(items[1].hidden,false);
run("filterPlayerEpisodes('missing');");assert.equal(empty.hidden,false);
run("filterPlayerEpisodes('');");assert.ok(items.every(x=>!x.hidden));
const extra={hidden:true,innerHTML:''},button={dataset:{name:'Episode B'},parentElement:{nextElementSibling:extra},setAttribute(k,v){this[k]=v}};
context.button=button;run('toggleEpisodeInfo(button)');assert.equal(extra.hidden,false);assert.ok(extra.innerHTML.includes('Episode B'));
run('toggleEpisodeInfo(button)');assert.equal(extra.hidden,true);assert.equal(extra.innerHTML,'');
console.log('Episode search, empty results, reset and lazy independent details passed');
let confirmAction, saved=0;
context.appConfirm=(title,body,label,action)=>confirmAction=action;
context.rememberCollections=()=>{};context.saveLocalTasks=()=>saved++;context.renderLocalTasks=()=>{};
vm.runInContext("let localTasks=[{id:'one'},{id:'two'}];\n"+['localTaskById','removeTaskRecord','localRemove'].map(source).join('\n'),context);
run("localRemove('one')");assert.equal(run('localTasks.length'),2,'Opening confirmation cannot remove a task');assert.equal(saved,0);
confirmAction();assert.equal(run('localTasks.length'),1);assert.equal(run('localTasks[0].id'),'two');assert.equal(saved,1);
console.log('Task record removal requires explicit confirmation');
