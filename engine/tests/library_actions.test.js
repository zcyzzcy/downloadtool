const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(name){const a=html.indexOf('function '+name+'(');assert.ok(a>=0);return html.slice(a,html.indexOf('\n}',a)+2);}
const elements=new Map();let deleted,hidden,refreshes=0,confirms=0;
function node(){return {buttons:new Map(),querySelector(key){if(!this.buttons.has(key))this.buttons.set(key,{});return this.buttons.get(key);},remove(){elements.delete(this.id);}};}
const context=vm.createContext({
 window:{__libRows:[{gkey:'First collection',items:[{name:'one.mp4'},{name:'two.mp4'}]}]},manage:false,
 document:{getElementById:id=>elements.get(id),createElement:()=>node(),body:{appendChild(el){elements.set(el.id,el);}}},
 AndroidBridge:{localDelete:names=>{deleted=JSON.parse(names);return JSON.stringify({ok:1,error:'second file retained'});}},
 appConfirm(title,description,label,ok){confirms++;ok();},hideRecords:names=>hidden=names,toast(){},clearTimeout(){},
 loadLib(){assert.equal(vm.runInContext('libLoading',context),false);refreshes++;},esc:s=>String(s),
});
vm.runInContext('let libRequestTimer=1,libLoading=true;\n'+['closeLibraryMenu','deleteLibraryNames','openLibraryMenu'].map(source).join('\n'),context);
const card={dataset:{gidx:'0'},closest(){return this;}};
context.openLibraryMenu(card);let menu=elements.get('library-actions');assert.ok(menu);
context.window.__libRows[0]={gkey:'Later collection',items:[{name:'unrelated.mp4'}]};
menu.querySelector('[data-action="delete"]').onclick();
assert.deepEqual(Array.from(deleted),['one.mp4','two.mp4']);assert.equal(refreshes,1);assert.equal(confirms,1);assert.equal(elements.has('library-actions'),false);
context.window.__libRows[0]={gkey:'New collection',items:[{name:'three.mp4'}]};context.openLibraryMenu(card);menu=elements.get('library-actions');
menu.querySelector('[data-action="hide"]').onclick();assert.deepEqual(Array.from(hidden),['three.mp4']);assert.equal(refreshes,1);
context.openLibraryMenu(card);elements.get('library-actions').querySelector('[data-action="cancel"]').onclick();assert.equal(confirms,2);assert.equal(elements.size,0);
console.log('File action targets survive list refreshes, partial deletion refreshes disk state, and hide/cancel remain separate');

card.isConnected=false;context.openLibraryMenu(card);assert.equal(elements.size,0);assert.equal(confirms,2);
console.log('Detached rows cannot open actions after an asynchronous list refresh');
// Task action menus capture the requested record, source and title at opening.
let task={id:'task-old',title:'Old title',url:'https://example.test/old'},copied,removed;
context.hManage=false;context.localTaskById=id=>task&&task.id===id?task:null;
context.taskDisplayTitle=t=>t.title;context.copyText=url=>copied=url;context.localRemove=id=>removed=id;
vm.runInContext(source('openTaskMenu'),context);
context.openTaskMenu('task-old');menu=elements.get('library-actions');assert.ok(menu);
task={id:'other',title:'Other',url:'https://example.test/other'};
menu.querySelector('[data-action="copy"]').onclick();assert.equal(copied,'https://example.test/old');
task={id:'task-old',title:'Old title',url:''};context.openTaskMenu('task-old');elements.get('library-actions').querySelector('[data-action="remove"]').onclick();assert.equal(removed,'task-old');
context.hManage=true;context.openTaskMenu('task-old');assert.equal(elements.size,0);
console.log('Task menus preserve source targets and stay closed in bulk management');
