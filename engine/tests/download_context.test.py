"""Exercise real media transfer headers, redirects and rapid service transitions."""
from pathlib import Path
import subprocess,tempfile,shutil,threading,base64
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
root=Path(__file__).resolve().parents[2]
engine=(root/'engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
service=(root/'engine/app/src/main/java/com/palworld/vd/VdService.java').read_text(encoding='utf-8')
def block(source,marker):
 start=source.index(marker);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
transfer=block(engine,'private void downloadTo(')
acquire=block(engine,'private static void svcAcquire(')
release=block(engine,'private static void svcRelease(')
assert service.index('startForeground(NOTIF_ID') < service.index('stopSelfResult(startId)')
requests=[]
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/h9sAAAAASUVORK5CYII=')
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  requests.append((self.path,self.headers.get('Cookie',''),self.headers.get('Referer',''),self.headers.get('User-Agent','')))
  if self.path=='/redirect':
   self.send_response(302);self.send_header('Location',f'http://localhost:{self.server.server_port}/end');self.send_header('Content-Length','0');self.end_headers();return
  payload = (b'<!doctype html>login' if self.path=='/html' else b'{"error":"expired"}' if self.path=='/json'
   else b'function pageScript() {}' if self.path=='/script' else b'\x89PNG' if self.path=='/short' else png)
  self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
java='''
import java.net.*;import java.io.*;import java.nio.file.*;import java.util.*;
class ContextTest {
 static final Object SVC_LOCK=new Object();static int svcCount;
 static class VdService {static final String ACTION_STOP="STOP";}
 static class Context {
  List<String> events=new ArrayList<>();
  void startForegroundService(android.content.Intent i){events.add("START");}
  void startService(android.content.Intent i){events.add(i.action==null?"START":i.action);}
 }
''' + transfer + acquire + release + '''
 public static void main(String[] args)throws Exception{
  Context ctx=new Context();svcAcquire(ctx);svcRelease(ctx);svcAcquire(ctx);svcRelease(ctx);
  if(!ctx.events.equals(Arrays.asList("START","STOP","START","STOP"))||svcCount!=0)throw new AssertionError();
  ctx.events.clear();svcAcquire(ctx);svcAcquire(ctx);svcRelease(ctx);if(ctx.events.size()!=1)throw new AssertionError();svcRelease(ctx);
  File out=new File(args[1]);new ContextTest().downloadTo(args[0]+"/direct",out,args[0]+"/bookmark","FixtureUA");
  if(!java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(out.toPath())).equals(args[2]))throw new AssertionError();
  new ContextTest().downloadTo(args[0]+"/redirect",out,args[0]+"/bookmark","FixtureUA");
  if(!java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(out.toPath())).equals(args[2]))throw new AssertionError();
  for(String suffix:new String[]{"html","json","script","short"}){
   try{new ContextTest().downloadTo(args[0]+"/"+suffix,out,args[0]+"/bookmark","FixtureUA");throw new AssertionError("accepted "+suffix);}
   catch(IOException expected){if(out.exists())throw new AssertionError("invalid output leaked");}
  }
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
 path=Path(directory);(path/'ContextTest.java').write_text(java,encoding='utf-8')
 (path/'MediaProbe.java').write_text((root/'engine/app/src/main/java/com/palworld/vd/MediaProbe.java').read_text(encoding='utf-8').replace('package com.palworld.vd;', ''),encoding='utf-8')
 stubs={
 'android/webkit/CookieManager.java':'''package android.webkit;public class CookieManager {public static CookieManager getInstance(){return new CookieManager();}public String getCookie(String u){return u.contains("127.0.0.1")?"audit_session=ok":null;}}''',
 'android/content/Intent.java':'''package android.content;public class Intent {public String action;public Intent(Object ctx,Class<?> cls){}public Intent setAction(String value){action=value;return this;}}''',
 'android/os/Build.java':'''package android.os;public class Build {public static class VERSION {public static int SDK_INT=31;}}'''}
 for name,content in stubs.items():
  p=path/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',*[str(p) for p in path.rglob('*.java')]],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'ContextTest',f'http://127.0.0.1:{server.server_port}',str(path/'out.mp4'),base64.b64encode(png).decode()],check=True)
server.shutdown()
assert len(requests)==7,requests
assert all(item[2].endswith('/bookmark') and item[3]=='FixtureUA' for item in requests),requests
assert requests[0][1]==requests[1][1]=='audit_session=ok',requests
assert requests[2][1]=='',requests
print('Transfer cookies, page headers, redirect-domain isolation and rapid service start/stop ordering passed')
