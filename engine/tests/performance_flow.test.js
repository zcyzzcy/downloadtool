const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function source(name) {
  const start = html.indexOf('function ' + name + '(');
  return html.slice(start, html.indexOf('\n}', start) + 2);
}
let rebuilds = 0, intervalStarts = 0, intervalStops = 0;
const cards = new Map(), nodes = new Map();
const node = () => ({style:{},classList:{toggle(){}},textContent:''});
const list = node();
Object.defineProperty(list, 'innerHTML', {set(value) {
  rebuilds++; cards.clear();
  for (const match of value.matchAll(/id="task-([^" ]+)"/g)) {
    const parts = {'.fill':node(),'.status':node(),'.task-progress':node()};
    cards.set('task-' + match[1], {querySelector: selector => parts[selector], parts});
  }
}});
nodes.set('list',list);nodes.set('home-ops',node());nodes.set('home-done-n',node());nodes.set('hmg-btn',node());nodes.set('task-filters',node());nodes.set('task-more',node());
const context = vm.createContext({
  rememberCollections(){},renderContinueWatching(){},
  document:{hidden:false,getElementById:id=>cards.get(id)||nodes.get(id)},window:{__browserOn:false},
  IN_APP:true,curView:'home',hManage:false,homeSel:new Set(),STATUS:{downloading:'下载中',parsing:'解析中'},
  localTasks:Array.from({length:80},(_,i)=>({id:String(i),status:i<77?'done':'downloading',files:[],file:'',title:'Task '+i})),
  healDoneTasks(){},probeTaskDur(){},doneRow:()=>'<div class="done"></div>',esc:value=>String(value),
  setInterval(){intervalStarts++;return intervalStarts;},clearInterval(){intervalStops++;},
});
vm.runInContext("let lastTaskRenderKey='',pollTimer=null,pollActive=false,homeTaskFilter='all',homeTaskLimit=12;\n"+
  ['progressSize','progLine','schedulePoll','updateLocalProgress','homeTaskRows','renderLocalTasks'].map(source).join('\n'),context);
context.renderLocalTasks();assert.equal(rebuilds,1);assert.equal(intervalStarts,1);
context.localTasks[77].progress=25;context.localTasks[77].downloadedBytes=1048576;
context.renderLocalTasks();assert.equal(rebuilds,1);assert.equal(intervalStarts,1);
assert.equal(cards.get('task-77').parts['.fill'].style.width,'25%');
context.localTasks[77].status='done';context.renderLocalTasks();assert.equal(rebuilds,2);
context.document.hidden=true;context.schedulePoll();assert.equal(vm.runInContext('pollTimer',context),null);
context.document.hidden=false;context.localTasks.forEach(task=>task.status='done');context.schedulePoll();
assert.equal(vm.runInContext('pollTimer',context),null);
context.localTasks[79].status='downloading';context.curView='lib';context.schedulePoll();
assert.equal(vm.runInContext('pollTimer',context),null);
console.log('80-task progress updates preserve DOM, idle and hidden views stop polling');

// Metadata work stays bounded and ignores events after timeout or completion.
const videos = [], timers = new Map();
let timerId = 0;
const probeContext = vm.createContext({
  curView:'home', document:{hidden:false,createElement(){
    const video = {removeAttribute(){},load(){},duration:12};
    videos.push(video);return video;
  }},window:{__browserOn:false},
  localTasks:[{id:'image',status:'done',files:['a.jpg']}, ...Array.from({length:5},(_,i)=>({
    id:'video'+i,status:'done',files:[],filename:'v'+i+'.mp4'
  }))],
  isImgName:name=>name.endsWith('.jpg'),fileUrl:name=>name,saveLocalTasks(){},renderLocalTasks(){},
  setTimeout(fn,delay){timers.set(++timerId,{fn,delay});return timerId;},
  clearTimeout(id){timers.delete(id);},isFinite,
});
vm.runInContext('const durProbing=new Set();\n'+source('probeTaskDur'),probeContext);
probeContext.localTasks.forEach(probeContext.probeTaskDur);
assert.equal(videos.length,2);
const late = videos[0].onloadedmetadata;
const timeout = [...timers.values()].find(timer=>timer.delay===10000);
timeout.fn();late();
assert.equal(probeContext.localTasks[1].dur,undefined);
assert.equal(probeContext.localTasks[1].durFail,1);
assert.equal(videos[0].onloadedmetadata,null);
[...timers.values()].filter(timer=>timer.delay===0).forEach(timer=>timer.fn());
assert.equal(videos.length,3);
videos[1].onloadedmetadata();
assert.equal(probeContext.localTasks[2].dur,12);
assert.equal(vm.runInContext('durProbing.size',probeContext),1);
probeContext.window.__browserOn=true;
probeContext.probeTaskDur(probeContext.localTasks[4]);assert.equal(videos.length,3);
console.log('Metadata concurrency, queued empty-file fallback and late-event cleanup passed');
