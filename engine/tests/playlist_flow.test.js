const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(name){const a=html.indexOf('function '+name+'(');assert.ok(a>=0);return html.slice(a,html.indexOf('\n}',a)+2);}
const store=new Map(),nodes=new Map();
const context=vm.createContext({
 localStorage:{setItem:(k,v)=>store.set(k,v)},kvGet:(k,d)=>store.get(k)||d,
 bridgeOk:()=>false,safeName:s=>String(s||''),doneByFile:()=>null,fmtDur:s=>s+'s',fmtDate:s=>String(s),cleanTitle:s=>s||'',isImgName:n=>/\.(jpg|png)$/i.test(n||''),
 fitPlayerStage(){},window:{scrollTo(){}},document:{getElementById:id=>{if(!nodes.has(id))nodes.set(id,{style:{},textContent:'',innerHTML:'',disabled:false,scrollTop:0,setAttribute(k,v){this[k]=v;}});return nodes.get(id);}},
 history:{pushState(){}},applyView(){},showPlFile(){},saveProgress(){},toast(){},esc:s=>String(s),gdOpen(){},
});
vm.runInContext('let savedCollections=null,localTasks=[],libItems=[],plSet=[],plIdx=0,curView="lib",plLibrarySet=[],plFileItems=[],plScope="all",playerDetailMode="",playerDetailResume=false,curPlName="",plMembership=new Map(),plDetailsCache=new Map();\n'+[
 'collectionCatalog','rememberCollections','collectionMembership','libGrouped','isVideoName','openPlayerSet','fileInfoOf','videoDetailsOf','availableCollectionNames','togglePlaylistScope','plSelect','plStep','renderPlaylistNav'
].map(source).join('\n'),context);
const run=s=>vm.runInContext(s,context);
run(`localTasks=Array.from({length:22},(_,i)=>({id:String(i),status:'done',collectionUrl:'https://collection/one',collectionTitle:'Series',collectionIndex:i+1,title:'Episode '+(i+1),files:['episode-'+(i+1)+'.mp4']})).reverse();
libItems=localTasks.map(t=>({name:t.files[0]}));`);
let groups=run('libGrouped(libItems)');assert.equal(groups.length,1);assert.equal(groups[0].items.length,22);assert.equal(groups[0].items[0].name,'episode-1.mp4');assert.equal(groups[0].items[21].name,'episode-22.mp4');
assert.ok(store.get('vd_collections').includes('Series'));
run('localTasks=[];savedCollections=null;');groups=run('libGrouped(libItems)');assert.equal(groups.length,1);assert.equal(groups[0].items.length,22);
run(`openPlayerSet(['episode-5.mp4'],0);`);assert.equal(run('plIdx'),4);assert.equal(run('plSet.length'),22);
run('renderPlaylistNav();');assert.equal(nodes.get('playlist-label').textContent,'Series');assert.equal(nodes.get('collection-count').textContent,'22 个视频 · 当前第 5 集');
run('plSelect(0);plStep(-1);');assert.equal(run('plIdx'),0);run('plSelect(21);plStep(1);');assert.equal(run('plIdx'),21);
run(`libItems=libItems.filter(i=>i.name!=='episode-6.mp4');openPlayerSet(['episode-5.mp4'],0);`);assert.equal(run('plSet.length'),21);
run(`localTasks=[{id:'other',status:'done',collectionUrl:'https://collection/two',collectionTitle:'Series',collectionIndex:1,files:['other.mp4']}];libItems.push({name:'other.mp4'});`);groups=run('libGrouped(libItems)');assert.equal(groups.length,2);
groups=run(`libGrouped([{name:'photo-2.jpg'},{name:'photo-1.jpg'},{name:'standalone.mp4'}])`);assert.equal(groups.length,2);assert.equal(groups[0].items[0].name,'photo-1.jpg');
console.log('Collection migration, independent persistence, episode order, missing files, distinct URLs, image groups and bounded navigation passed');
const begin=html.indexOf('function nvPoll()');const finish=html.indexOf('\n}, 400);',begin);
vm.runInContext('let nvCur="",nvState="",nvPos=0,nvDur=0,nvFull=false;\n'+html.slice(begin,finish+2),context);
context.bridgeOk=()=>true;context.AndroidBridge={videoState:()=> 'ended,3000,3000,0'};
run(`curView='player';plSet=['one.mp4','two.mp4'];plIdx=0;nvCur='one.mp4';nvPoll();`);assert.equal(run('plIdx'),1);
run(`nvCur='two.mp4';nvPoll();nvPoll();`);assert.equal(run('plIdx'),1);
console.log('Automatic next episode stops at the end of the playlist');
context.AndroidBridge.videoState=()=> 'ended,3000,3000,0,640,360,previous.mp4';
run(`plIdx=0;nvCur='one.mp4';nvPoll();`);assert.equal(run('plIdx'),0);
context.AndroidBridge.videoState=()=> 'ended,3000,3000,0,640,360,one.mp4';
run('nvPoll();');assert.equal(run('plIdx'),1);
console.log('Late previous-video states cannot advance the current episode');
context.bridgeOk=()=>false;
run(`savedCollections=[];localTasks=[
 {id:'a',status:'done',collectionUrl:'https://series',collectionTitle:'Series details',collectionIndex:1,title:'Episode A',raw:'Description A',url:'https://video/a',files:['episode-a.mp4']},
 {id:'b',status:'done',collectionUrl:'https://series',collectionTitle:'Series details',collectionIndex:2,title:'Episode B',raw:'Description B',url:'https://video/b',files:['episode-b.mp4']}
];libItems=['before.mp4','episode-b.mp4','episode-a.mp4','after.mp4','sound.mp3','image.jpg'].map(name=>({name,sizeMB:3,mtime:123}));
showPlFile=function(){curPlName=plSet[plIdx];};openPlayerSet(['episode-b.mp4'],0);`);
assert.deepEqual(Array.from(run('plSet')),['before.mp4','episode-a.mp4','episode-b.mp4','after.mp4']);
assert.equal(run('videoDetailsOf("episode-a.mp4").raw'),'Description A');
assert.equal(run('videoDetailsOf("episode-b.mp4").url'),'https://video/b');
run('togglePlaylistScope();');assert.equal(run('plSet.length'),2);assert.equal(run('plIdx'),1);
run('togglePlaylistScope();plStep(1);');assert.equal(run('curPlName'),'after.mp4');assert.equal(run('videoDetailsOf(curPlName).collection'),undefined);
run(`localTasks=[];savedCollections=null;plDetailsCache.clear();plMembership=collectionMembership();`);
assert.equal(run('videoDetailsOf("episode-b.mp4").raw'),'Description B');
assert.equal(run('videoDetailsOf("episode-a.mp4").url'),'https://video/a');
context.thumbBox=name=>'<img data-file="'+name+'">';
vm.runInContext(['detailMeta','detailsBodyHtml','renderPlayerDetails'].map(source).join('\n'),context);
run(`curPlName='episode-a.mp4';playerDetailMode='collection';renderPlayerDetails();`);
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('Episode A'));
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('Episode B'));
assert.ok(!nodes.get('player-detail-panel').innerHTML.includes('<img'));
run(`playerDetailMode='video';renderPlayerDetails();`);
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('Description A'));
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('https://video/a'));
run(`curPlName='episode-b.mp4';renderPlayerDetails();`);
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('Description B'));
assert.ok(nodes.get('player-detail-panel').innerHTML.includes('https://video/b'));
let scanned;context.scanNamesToPhone=names=>scanned=names;
vm.runInContext(source('saveToPhone'),context);context.saveToPhone();
assert.deepEqual(Array.from(scanned),['episode-b.mp4']);
console.log('Standalone navigation, cross-collection boundaries, audio exclusion, scope choice and durable per-episode details passed');
run(`localTasks=[{id:'generic',status:'done',title:'download',files:['download(9).mp4']}];libItems=[{name:'download(9).mp4'}];openPlayerSet(['download(9).mp4'],0);`);
assert.equal(run('videoDetailsOf("download(9).mp4").title'),'download(9)');
context.bridgeOk=()=>true;context.AndroidBridge={videoState:()=> 'ended,3000,3000,0'};
run(`curView='player';playerDetailMode='queue';plSet=['first.mp4','last.mp4'];plIdx=0;nvCur='first.mp4';nvPoll();`);
assert.equal(run('plIdx'),1);assert.equal(run('playerDetailMode'),'queue');
console.log('Generic saved titles retain distinct filenames and automatic playback keeps the open list');
let revealScroll=0, restored=false, panelTop=1000;
context.window.innerHeight=1080;
context.window.scrollBy=opts=>revealScroll=opts.top;
const panel=nodes.get('player-detail-panel');
panel.querySelector=()=>null;
panel.getBoundingClientRect=()=>({top:panelTop});
const entry=nodes.get('collection-detail-button');
entry.getBoundingClientRect=()=>({top:-100});
entry.scrollIntoView=()=>restored=true;
vm.runInContext(['togglePlayerDetails','closePlayerDetails'].map(source).join('\n'),context);
run(`playerDetailMode='';togglePlayerDetails('collection');`);
assert.ok(revealScroll>0,'Below-fold collection must become visible');
run('closePlayerDetails();');
assert.ok(restored,'Closing a scrolled panel reveals its entry');
assert.equal(panel.innerHTML,'');
panelTop=300;revealScroll=0;
run(`togglePlayerDetails('collection');`);
assert.equal(revealScroll,0,'Already visible content must not jump');
console.log('Below-fold expansion reveals content, collapse restores entry and visible panels do not jump');

run(`savedCollections=[];localTasks=[{id:'mixed-images',status:'done',mediaCollection:true,collectionUrl:'https://community/post',collectionTitle:'Mixed',collectionIndex:1,files:['image-10.jpg','image-2.jpg','image-1.jpg']},{id:'mixed-video',status:'done',mediaCollection:true,collectionUrl:'https://community/post',collectionTitle:'Mixed',collectionIndex:2,files:['video.mp4']}];`);
assert.equal(JSON.stringify(run("collectionMembership().get('image-10.jpg').items.map(i=>i.name)")),JSON.stringify(['image-10.jpg','image-2.jpg','image-1.jpg','video.mp4']));
console.log('Mixed post images preserve manifest order within their part, followed by the video part');
