"""Exercise the production task dispatch and semaphore ownership across host replacement."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')


def block(marker):
    start = source.index(marker)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


constructor = block('synchronized (LocalEngine.class)')
methods = '\n'.join(block(marker) for marker in [
    'private void postState(', 'private static void dPostState(',
    'private static void dPostProgress(', 'public void acknowledgeTask('])
java = r"""
import java.util.*;
import java.util.concurrent.*;
class LocalEngine {
 static LocalEngine sActive;
 static Semaphore sharedSlots;
 final Semaphore slots;
 boolean detached;
 static Map<String,String[]> pendingTaskStates = new ConcurrentHashMap<>();
 static final List<Runnable> callbacks = new ArrayList<>();
 final Handler main = new Handler();
 final Listener listener = new Listener();
 static class Handler {void post(Runnable r){callbacks.add(r);}}
 static class Listener {
  int states, progress;
  void onState(String id,String state,String title,String error,String files){states++;}
  void onProgress(String id,int percent,String line){progress++;}
 }
 LocalEngine(int n){sActive=this;
""" + constructor + '}' + methods + r"""
 static void drain(){while(!callbacks.isEmpty())callbacks.remove(0).run();}
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  LocalEngine old=new LocalEngine(2);old.slots.acquire(2);
  LocalEngine next=new LocalEngine(2);check(next.slots==old.slots && next.slots.availablePermits()==0);
  old.slots.release();check(next.slots.tryAcquire());check(!next.slots.tryAcquire());
  old.detached=true;old.postState("native-done","done","Title",null,"[]");
  drain();check(old.listener.states==0 && next.listener.states==1);
  dPostState("queued-done","done",null,null,"[]");dPostProgress("active",50,"50%");
  next.detached=true;LocalEngine newer=new LocalEngine(2);drain();
  check(next.listener.states==1 && newer.listener.states==1 && newer.listener.progress==1);
  check(pendingTaskStates.containsKey("queued-done"));newer.acknowledgeTask("queued-done");
  check(!pendingTaskStates.containsKey("queued-done"));
  newer.detached=true;sActive=null;dPostState("gap-done","done","Gap",null,"[a.mp4]");
  check(pendingTaskStates.get("gap-done")[4].equals("[a.mp4]"));
  LocalEngine restored=new LocalEngine(2);String[] event=pendingTaskStates.get("gap-done");
  dPostState(event[0],event[1],event[2],event[3],event[4]);drain();
  check(restored.listener.states==1);restored.acknowledgeTask("gap-done");
  check(!pendingTaskStates.containsKey("gap-done"));
 }
}
"""
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'LocalEngine.java'
    path.write_text(java, encoding='utf-8')
    subprocess.run([shutil.which('javac'), '-encoding', 'UTF-8', str(path)], check=True)
    subprocess.run([shutil.which('java'), '-Xmx128m', '-XX:ReservedCodeCacheSize=32m', '-cp', directory, 'LocalEngine'], check=True)
print('Shared concurrency, current-page dispatch and completion-gap recovery checks passed')
