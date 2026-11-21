"""Run production gallery completion on full, partial and failed batches."""
from pathlib import Path
import subprocess, shutil, tempfile

root = Path(__file__).resolve().parents[2]
source = (root/'engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
start = source.index('    public String rescueSubmit(')
end = source.index('\n    }', start) + 6
method = source[start:end]
jar = root/'build/test-deps/json-20240303.jar'
assert jar.exists(), 'Run magnet_stream.test.py first to acquire the JSON test dependency'
java = '''
import java.util.*;import java.util.concurrent.*;import org.json.*;
class GalleryCheck {
 final Semaphore slots=new Semaphore(1),tcSlots=new Semaphore(1);
 final Map<String,Thread> threads=new ConcurrentHashMap<>();final Map<String,Object> stagingDirs=new ConcurrentHashMap<>();
 final Object ctx=new Object();int count;boolean registered;String[] result;CountDownLatch finished;
 static void svcAcquire(Object c){}static void svcRelease(Object c){}
 String newId(){return "fixture";}String sanitizeName(String n){return n;}
 String errMsg(Throwable t){return t.toString();}boolean isVideoName(String n){return n.endsWith(".mp4");}
 String normalizeVideo(String n,String id,String title){return n;}
 void tcEnter()throws InterruptedException{tcSlots.acquire();}
 void freeSlot(boolean[] freed){if(!freed[0]){freed[0]=true;slots.release();}}
 void postState(String id,String state,String title,String error,String files){
  if(state.equals("done")||state.equals("error")){
   if(files!=null&&!registered)throw new AssertionError("published before registering saved files");
   result=new String[]{state,error,files};finished.countDown();
  }
 }
 void scanToGallery(List<String> f){}void markOwned(List<String> f){registered=true;}void ensureThumbs(List<String> f){}
 List<String> downloadMediaUrls(String id,List<String> urls,String base,String referer,String agent,Set<String> videoUrls){
  if(!videoUrls.contains("https://cdn.test/opaque-video"))throw new AssertionError("lost explicit media type");
  return new ArrayList<>(Arrays.asList("one.mp4","two.jpg").subList(0,count));
 }
''' + method + '''
 public static void main(String[] args)throws Exception {
  for(int n=0;n<=2;n++){
   GalleryCheck g=new GalleryCheck();g.count=n;g.finished=new CountDownLatch(1);
   g.rescueSubmit("{\\"desc\\":\\"Fixture\\",\\"videos\\":[\\"https://cdn.test/opaque-video\\"],\\"images\\":[\\"https://cdn.test/photo\\"]}");
   if(!g.finished.await(10,TimeUnit.SECONDS))throw new AssertionError("no completion");
   if(!g.result[0].equals(n==2?"done":"error"))throw new AssertionError("partial falsely completed");
   if(n>0&&new JSONArray(g.result[2]).length()!=n)throw new AssertionError("lost saved media");
   if(n==1&&!g.result[1].contains("1/2"))throw new AssertionError("missing count");
  }
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
    path=Path(directory)/'GalleryCheck.java';path.write_text(java,encoding='utf-8')
    subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8','-cp',str(jar),str(path)],check=True)
    subprocess.run([shutil.which('java'),'-Xmx128m','-cp',str(jar)+';'+directory,'GalleryCheck'],check=True)
print('Complete/partial/failed gallery states, saved-file retention and explicit video typing passed')
