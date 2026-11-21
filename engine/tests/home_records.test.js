const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(n){const a=html.indexOf('function '+n+'(');return html.slice(a,html.indexOf('\n}',a)+2);}
let played;
const ctx=vm.createContext({window:{},esc:s=>String(s),toast(){},openPlayerSet:(...a)=>played=a});
vm.runInContext('let localTasks=[];\n'+['taskErrorSummary','taskDisplayTitle','homeTaskRows','homeCollectionRow','openHomeCollection'].map(source).join('\n'),ctx);
const run=s=>vm.runInContext(s,ctx);
run(`localTasks=[{id:'a',status:'done',collectionUrl:'series',collectionTitle:'Series',collectionIndex:1,collectionTotal:3,sizeMB:2,files:['a.mp4']},
{id:'b',status:'done',collectionUrl:'series',collectionTitle:'Series',collectionIndex:2,collectionTotal:3,sizeMB:3,files:['b.mp4']},
{id:'failed',status:'error',collectionUrl:'series',collectionIndex:3},
{id:'active',status:'downloading',collectionUrl:'series'},
{id:'other',status:'done',collectionUrl:'other',collectionTitle:'Series',files:['other.mp4']}];`);
let rows=run("homeTaskRows(localTasks,'all',false)");
assert.equal(rows.length,4);assert.equal(rows[0].task.id,'active');assert.equal(rows[1].kind,'failures');assert.equal(rows[1].tasks[0].id,'failed');
const group=rows.find(r=>r.url==='series');assert.equal(group.tasks.length,2);
ctx.group=group;assert.ok(run('homeCollectionRow(group)').includes('2/3 集已完成'));
assert.equal(run("homeTaskRows(localTasks,'done',false).length"),2);
assert.equal(run("homeTaskRows(localTasks,'error',false)[0].task.id"),'failed');
assert.equal(run("homeTaskRows(localTasks,'active',false)[0].task.id"),'active');
assert.equal(run("homeTaskRows(localTasks,'all',true).length"),3,'Management exposes original completed records');
run("openHomeCollection('series')");assert.deepEqual(Array.from(played[0]),['a.mp4','b.mp4']);
assert.equal(run('localTasks.length'),5,'Display grouping must not alter persisted records');
assert.ok(!html.includes("openTool('qr')"));
const manifest=fs.readFileSync('engine/app/src/main/AndroidManifest.xml','utf8');
assert.ok(manifest.includes('下载到百宝箱'));assert.ok(!manifest.includes('QrActivity'));
console.log('Task priority, status filters, partial collections, distinct URLs, original-record management and collection playback passed');

assert.equal(run("taskErrorSummary('ERROR: [zhuliwa] abc-def: 作品尚未解锁')"),'内容尚未解锁，请先购买或解锁后再试');
assert.equal(run("taskDisplayTitle({title:'download',files:['download(9).mp4']})"),'download(9)');

run("localTasks.push({id:'second-failure',status:'error'});");assert.equal(run("homeTaskRows(localTasks,'all',false).find(row=>row.kind==='failures').tasks.length"),2);assert.equal(run("homeTaskRows(localTasks,'error',false).length"),2);

ctx.homeSel=new Set();ctx.platformOf=()=>'';ctx.tagLine=()=>'';ctx.isImgName=n=>/\.(jpg|png)$/i.test(n);
vm.runInContext(['isVideoName','typeBadge','doneRow'].map(source).join('\n'),ctx);
assert.ok(run("doneRow({id:'bt',files:['a.mp4','b.mkv','readme.txt']})").includes('视频 · 2 集'),'BT attachments do not count as episodes');
assert.ok(!run("doneRow({id:'bt',files:['a.mp4','b.mkv','readme.txt']})").includes('视频 · 3 集'));
assert.ok(run("doneRow({id:'text',files:['readme.txt']})").includes('文件 · 1 个'));
console.log('BT text attachments are excluded from episode counts');
