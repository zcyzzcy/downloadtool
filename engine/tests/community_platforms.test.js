const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict'), path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/community_collect.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function source(name) { const i = html.indexOf('function ' + name + '('); return html.slice(i, html.indexOf('\n}', i) + 2); }
const routes = vm.createContext({URL});
vm.runInContext(['communityKindOf','galleryKindOf','fallbackKindOf'].map(source).join('\n'), routes);
for (const [url,kind] of [
  ['https://tieba.baidu.com/p/12345?pn=2','tieba'], ['https://tieba.baidu.com/mo/q/m?kz=12345','tieba'],
  ['https://www.miyoushe.com/dby/article/12345','miyoushe'], ['https://www.hoyolab.com/article/12345','miyoushe'],
  ['https://www.kurobbs.com/postDetail.html?postId=12345','kurobbs'], ['https://www.kurobbs.com/pns/post/12345','kurobbs'],
  ['https://www.taptap.cn/moment/12345','taptap'],
]) {
  assert.equal(routes.galleryKindOf(url).kind, kind);
  assert.equal(routes.fallbackKindOf(url, 'Unsupported URL'), kind);
}
for (const url of ['https://tieba.baidu.com.evil.test/p/12345','https://evil.test/?site=miyoushe.com','https://cdn.kurobbs.com/a.mp4','file:///miyoushe.com'])
  assert.equal(routes.communityKindOf(url),null);

function make(url, responses, document = {}) {
  const calls = [], u = new URL(url);
  const c = vm.createContext({URL, AbortController, setTimeout, clearTimeout, location: {href:url,pathname:u.pathname},
    document:{title:'Selected post',...document}, window:{}, localStorage:{getItem:k=>k==='auth_token'?'fixture-auth':k==='dc'?'fixture-device':''},
    fetch:async(url,options)=>{calls.push({url,options});const response = responses.find(([match])=>url.includes(match));assert.ok(response,url);return {ok:true,json:async()=>response[1]};},
  });
  vm.runInContext(script,c);
  return {calls,c,read:site=>JSON.parse(vm.runInContext(`vdCollectCommunity('${site}')`,c))};
}
async function settle(test,site) { for(let i=0;i<10;i++){const r=test.read(site);if(r.media||r.error)return r;await new Promise(r=>setImmediate(r));}throw Error('Parser never settled'); }
(async()=>{
  const post={post:{post_id:'12345',subject:'MYS original',images:['1','2']},image_list:[{url:'https://cdn.test/a.jpg',is_user_set_cover:true},{url:'https://cdn.test/b.jpg'}],
    vod_list:[{resolutions:[{width:640,height:360,url:'https://cdn.test/small.mp4'},{width:1920,height:1080,url:'https://cdn.test/full.mp4'}]},
      {resolutions:[{url:'https://cdn.test/full.m3u8?sig=keep'}]}],cover:{url:'https://cdn.test/cover.jpg'},hot_reply_list:[{url:'https://cdn.test/comment.jpg'}]};
  let test=make('https://www.miyoushe.com/dby/article/12345',[['getPostFull',{retcode:0,data:{post}}]]);
  let r=await settle(test,'miyoushe');assert.equal(r.media.images.length,2);assert.equal(r.media.desc,'MYS original');
  assert.deepEqual(r.media.videos,['https://cdn.test/full.mp4']);assert.deepEqual(r.media.streams,['https://cdn.test/full.m3u8?sig=keep']);assert.equal(test.calls.length,1);assert.equal(test.calls[0].options.credentials,'include');
  post.vod_list.push({});assert.match(test.read('miyoushe').error,/不完整/);post.vod_list.pop();
  post.image_list[0].is_deleted=true;assert.match(test.read('miyoushe').error,/不完整/);post.image_list[0].is_deleted=false;
  post.post.post_id='wrong';assert.match(test.read('miyoushe').error,/不匹配/);post.post.post_id='12345';
  test=make('https://www.hoyolab.com/article/12345',[['bbs-api-os.hoyolab.com',{retcode:-100}]]);
  assert.match((await settle(test,'miyoushe')).error,/登录/);

  const detail={id:'12345',postTitle:'Kuro original',postContent:[{contentType:1,url:'https://cdn.test/text-icon.jpg'},
    {contentType:2,url:'https://cdn.test/main.jpg'},{contentType:4,url:'https://cdn.test/divider.png'}]};
  test=make('https://www.kurobbs.com/pns/post/12345',[['getPostDetail',{code:200,data:{postDetail:detail,comment:[{url:'https://cdn.test/comment.jpg'}]}}]]);
  r=await settle(test,'kurobbs');assert.deepEqual(r.media.images,['https://cdn.test/main.jpg']);
  assert.equal(test.calls[0].options.headers.source,'h5');assert.equal(test.calls[0].options.headers.token,'fixture-auth');
  detail.videoId='opaque-vod-id';assert.match(test.read('kurobbs').error,/播放凭证/);delete detail.videoId;
  detail.postContent[1].isAbnormal=true;assert.match(test.read('kurobbs').error,/不完整|没有/);

  const data={moment:{id_str:'12345',topic:{title:'Tap original',images:[{url:'https://cdn.test/cover.jpg'}]}},
    first_post:{is_topic:true,contents:{json:[{type:'image',info:{image:{original_url:'https://cdn.test/photo.png'}}},
      {type:'video',info:{video_id:11,image:{original_url:'https://cdn.test/video-cover.png'}}},
      {type:'video',info:{video_id:22}}]},footer_images:[{original_url:'https://cdn.test/footer.jpg'}]}};
  const videoList=[{video_id:22,play_url:{url:'https://cdn.test/22.m3u8'}},{video_id:11,play_url:{url:'https://cdn.test/11.m3u8'}}];
  test=make('https://www.taptap.cn/moment/12345',[['moment/v3/detail',{data}],['video-resource',{data:{list:videoList}}]]);
  r=await settle(test,'taptap');assert.deepEqual(r.media.images,['https://cdn.test/photo.png','https://cdn.test/footer.jpg']);
  assert.deepEqual(r.media.streams,['https://cdn.test/11.m3u8','https://cdn.test/22.m3u8']);assert.deepEqual(r.media.videos,[]);assert.equal(test.calls.length,2);
  videoList.pop();assert.match(test.read('taptap').error,/不完整/);
  data.first_post.is_topic=false;assert.match(test.read('taptap').error,/完整动态正文/);

  const body={querySelectorAll:s=>s==='img.BDE_Image'?[{getAttribute:k=>k==='data-original'?'https://cdn.test/original.jpg':''}]:[],querySelector:()=>null};
  const posts=[{getAttribute:()=>'{"content":{"post_no":2}}',querySelector:()=>{throw Error('Read reply');}},
    {getAttribute:()=>'{"content":{"post_no":1}}',querySelector:()=>body}];
  test=make('https://tieba.baidu.com/p/12345',[],{querySelectorAll:()=>posts,querySelector:()=>({textContent:'Thread title'})});
  r=test.read('tieba');assert.deepEqual(r.media.images,['https://cdn.test/original.jpg']);assert.match(r.media.desc,/主帖正文/);
  assert.match(make('https://tieba.baidu.com/f?kw=foo',[]).read('tieba').error,/具体帖子/);
  console.log('Community routing, exact-post API identity, original images, cover/comment exclusion, streaming manifests, unavailable media and Tieba first-post boundary passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
