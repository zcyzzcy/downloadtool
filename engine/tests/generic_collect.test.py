"""Execute the Java-embedded JS to keep unrelated network resources out of galleries."""
from pathlib import Path
import json, re, subprocess, tempfile

root = Path(__file__).resolve().parents[2]
source = (root/'engine/app/src/main/java/com/palworld/vd/MainActivity.java').read_text(encoding='utf-8')
section = source.split('private static final String EXPR_ANY =', 1)[1].split('private static final String UA_DESKTOP', 1)[0]
parts = []
for line in section.splitlines():
    match = re.match(r'\s*("(?:[^"\\]|\\.)*")\s*([+;])', line)
    if match:
        parts.append(json.loads(match[1]))
        if match[2] == ';': break
expression = ''.join(parts)
assert expression
test = r'''
const vm=require('node:vm'),assert=require('node:assert/strict');
let images=[],videos=[],requests=[];
const scope={querySelectorAll:s=>s==='img'?images:videos};
const document={title:'Article',querySelector:s=>s.includes('article')?scope:null,
 querySelectorAll:()=>{throw Error('Must not scan the whole document')},
 documentElement:{outerHTML:'<script>"https://cdn.test/recommended.mp4"</script>'}};
function collect(){return JSON.parse(vm.runInNewContext(EXPRESSION,{document,
 performance:{getEntriesByType:()=>requests.map(name=>({name}))}}));}
requests=['https://assets.aliyuncs.com/app.js','https://assets.aliyuncs.com/recommendation.jpg'];
assert.equal(collect().media,null);
images=[{src:'https://bbs.hycdn.cn/image/body.webp',closest:()=>null},
 {src:'https://bbs.hycdn.cn/image/comment.webp',closest:()=>({})}];
// hycdn is used by the dedicated parser; generic extraction uses established hosts.
images[0].src='https://img.skland.com/image/body.webp';
images[1].src='https://img.skland.com/image/comment.webp';
assert.deepEqual(collect().media.images,['https://img.skland.com/image/body.webp']);
images=[];videos=[{currentSrc:'blob:local',src:'blob:local'}];requests=['https://cdn.test/video.mp4'];
assert.deepEqual(collect().media.videos,requests);
requests.push('https://cdn.test/advert.mp4');assert.equal(collect().media,null);
videos=[{currentSrc:'https://cdn.test/direct.mp4',src:'https://cdn.test/direct.mp4'}];
assert.deepEqual(collect().media.videos,['https://cdn.test/direct.mp4']);
console.log('Scoped正文, excluded comments/scripts/images, no global MP4 scraping, ambiguous blob request rejection passed');
'''.replace('EXPRESSION', json.dumps(expression))
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory)/'generic.js'; path.write_text(test, encoding='utf-8')
    subprocess.run(['node', str(path)], check=True)
