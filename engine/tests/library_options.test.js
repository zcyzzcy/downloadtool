const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('engine/app/src/main/assets/index.html','utf8');
function source(n){const a=html.indexOf('function '+n+'(');return html.slice(a,html.indexOf('\n}',a)+2);}
let renders=0;const saved=new Map();
const c=vm.createContext({manage:false,localStorage:{setItem:(k,v)=>saved.set(k,v)},renderLib:()=>renders++,isImgName:n=>/\.(png|jpg)$/i.test(n),isVideoName:n=>/\.mp4$/i.test(n)});
vm.runInContext("let libType='all',libSort='newest';\n"+['setLibraryOptions','sortedLibraryRows'].map(source).join('\n'),c);
const run=s=>vm.runInContext(s,c);
run(`rows=[{type:'group',gkey:'Series',items:[{name:'episode-1.mp4',mtime:1,sizeMB:2},{name:'episode-2.mp4',mtime:30,sizeMB:4}]},
{type:'single',item:{name:'photo.jpg',mtime:10,sizeMB:9}},
{type:'single',item:{name:'clip-10.mp4',mtime:2,sizeMB:1}},
{type:'single',item:{name:'clip-2.mp4',mtime:3,sizeMB:1}},
{type:'single',item:{name:'sound.mp3',mtime:4,sizeMB:1}}];`);
assert.equal(run("sortedLibraryRows(rows,'all','newest')[0].gkey"),'Series');
assert.equal(run("sortedLibraryRows(rows,'all','size')[0].item.name"),'photo.jpg');
assert.equal(run("sortedLibraryRows(rows,'video','name')[0].item.name"),'clip-2.mp4');
assert.equal(run("sortedLibraryRows(rows,'image','newest').length"),1);
assert.equal(run("sortedLibraryRows(rows,'video','newest').length"),3);
assert.equal(run("rows[0].items[0].name"),'episode-1.mp4','Sorting groups must preserve internal episode order');
assert.equal(run('rows.length'),5,'Filtering must not mutate source');
run("setLibraryOptions('type','video');setLibraryOptions('sort','size');");assert.equal(renders,2);assert.equal(saved.get('vd_lib_type'),'video');
run("setLibraryOptions('sort','unknown');manage=true;setLibraryOptions('type','all');");assert.equal(renders,2);assert.equal(run('libType'),'video');
console.log('File types, aggregate sort, natural names, episode order, persistence and management guards passed');
