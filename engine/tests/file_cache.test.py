"""Check production file-list caching and invalidation with real temporary files."""
from pathlib import Path
import subprocess, tempfile, shutil
root=Path(__file__).resolve().parents[2]
source=(root/'engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
def block(marker):
    start=source.index(marker);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(block(marker) for marker in ['public synchronized String refreshFiles(', 'public synchronized String listFiles('])
methods=methods.replace('android.os.SystemClock.elapsedRealtime()', 'clock')
java='''
import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;import java.util.regex.*;
class CacheTest {
 static long clock=100;static AtomicLong mediaRevision=new AtomicLong();
 String filesCacheKey="",filesCacheJson="[]";long filesCacheAt;
 CountingFile vdDir;String mode="public";
 static class CountingFile extends File {int scans;CountingFile(String p){super(p);}public File[] listFiles(){scans++;return super.listFiles();}}
 static class Prefs {String hidden="[]";String getString(String k,String fallback){return hidden;}}
 Prefs prefs=new Prefs();Set<String> owned=new HashSet<>();
 String dirMode(){return mode;}
 HashSet<String> ownedSet(){return new HashSet<>(owned);}
 HashSet<String> hiddenNames(){return prefs.hidden.equals("[]")?new HashSet<>():new HashSet<>(Arrays.asList("a.mp4"));}
 static Pattern MEDIA_NAME=Pattern.compile(".*\\\\.(mp4|jpg)");
 boolean isVideoName(String n){return n.endsWith(".mp4");}
 void ensureThumbs(List<String> names){}
 static class JSONObject extends LinkedHashMap<String,Object>{}
 static class JSONArray extends ArrayList<Object>{void put(Object o){add(o);}}
''' + methods + '''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  CacheTest t=new CacheTest();t.vdDir=new CountingFile(args[0]);
  new File(t.vdDir,"a.mp4").createNewFile();String initial=t.listFiles();check(initial.contains("a.mp4") && t.vdDir.scans==1);
  t.listFiles();check(t.vdDir.scans==1);
  t.refreshFiles();check(t.vdDir.scans==2);
  mediaRevision.incrementAndGet();t.listFiles();check(t.vdDir.scans==3);
  t.prefs.hidden="[a.mp4]";check(!t.listFiles().contains("a.mp4") && t.vdDir.scans==4);
  t.prefs.hidden="[]";t.mode="custom";check(!t.listFiles().contains("a.mp4"));
  t.owned.add("a.mp4");mediaRevision.incrementAndGet();check(t.listFiles().contains("a.mp4"));
  int before=t.vdDir.scans;clock+=3001;t.listFiles();check(t.vdDir.scans==before+1);
  t.vdDir=new CountingFile(args[1]);check(t.listFiles().equals("[]") && t.vdDir.scans==1);
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
    path=Path(directory);(path/'one').mkdir();(path/'two').mkdir()
    (path/'CacheTest.java').write_text(java,encoding='utf-8')
    subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(path/'CacheTest.java')],check=True)
    subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'CacheTest',str(path/'one'),str(path/'two')],check=True)
print('File-list reuse, refresh, revision, hidden files, ownership, expiry and directory changes passed')
