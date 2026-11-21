"""Exercise production BT output parsing and stall detection against repeated summaries."""
from pathlib import Path
import subprocess,shutil,tempfile
s=Path('engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
start=s.index('    public String magnetSubmit(');end=s.index('    /** 图集/笔记救援',start);magnet=s[start:end]
constants=[]
for name in ['BT_SUMMARY','MAGNET_PEER_HINT']:
 start=s.index('    private static final java.util.regex.Pattern '+name);end=s.index(';',start)+1;constants.append(s[start:end])
start=magnet.index('Thread reader = new Thread(');end=magnet.index('\n                    reader.setDaemon',start)
reader=magnet[start:end].replace('System.currentTimeMillis()', 'clock()')
assert magnet.index('if (Thread.currentThread().isInterrupted()) throw new InterruptedException') < magnet.index('final Process p = pb.start()')
assert 'Process remaining = btProcs.remove(id);' in magnet
assert 'if (remaining != null) FfmpegTool.destroyProcess(remaining);' in magnet
java='''
import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;
class MagnetCheck {
 static int ticks,states;static List<JSONObject> events=new ArrayList<>();
 static long clock(){return ++ticks;}
 static void postState(String id,String state,String title,String error,String files){states++;}
 static void dPostProgress(String id,int percent,String json){events.add(JSONObject.all.get(Integer.parseInt(json)));}
 static class JSONObject extends LinkedHashMap<String,Object>{static List<JSONObject> all=new ArrayList<>();JSONObject(){all.add(this);}public String toString(){return String.valueOf(all.indexOf(this));}}
 static class FakeProcess {String text;FakeProcess(String s){text=s;}InputStream getInputStream(){return new ByteArrayInputStream(text.getBytes());}}
''' + '\n'.join(constants) + '''
 public static void main(String[] args)throws Exception {
  if(!MAGNET_PEER_HINT.matcher("magnet:?xt=urn:btih:abc&tr=http%3A%2F%2Ftracker").find())throw new AssertionError();
  if(!MAGNET_PEER_HINT.matcher("magnet:?xt=urn:btih:abc&x.pe=127.0.0.1:8767").find())throw new AssertionError();
  if(MAGNET_PEER_HINT.matcher("magnet:?xt=urn:btih:abc&tr=").find())throw new AssertionError();
  final FakeProcess p=new FakeProcess("[#a 0B/0B(0%) CN:0 SD:0 DL:0B]\\n[#a 0B/10000B(0%) CN:1 SD:1 DL:0B]\\n[#a 1000B/10000B(10%) CN:2 SD:1 DL:500B]\\n[#a 1000B/10000B(10%) CN:2 SD:1 DL:0B]\\n[#a 2000B/10000B(20%) CN:2 SD:1 DL:500B]\\n");
  final AtomicLong lastProgressAt=new AtomicLong();final StringBuilder tail=new StringBuilder();final String id="bt";
''' + reader + '''
  reader.start();reader.join();
  if(ticks!=2 || lastProgressAt.get()!=2 || states!=1 || events.size()!=4)throw new AssertionError("Repeated zero/unchanged progress reset timeout");
  JSONObject event=events.get(1);
  if(!event.get("downloaded_bytes").equals(1000L) || !event.get("total_bytes").equals(10000L) || !event.get("speed").equals(500L))throw new AssertionError();
  if(!event.get("note").equals("连接节点 2 · 做种 1"))throw new AssertionError();
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory)/'MagnetCheck.java';p.write_text(java,encoding='utf-8')
 subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(p)],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'MagnetCheck'],check=True)
print('BT exact progress/peer metrics, stalled-data clock and cancellation ownership checks passed')
