"""Exercise production resident-page eviction and collector queue cancellation."""
from pathlib import Path
import subprocess
import tempfile
import shutil
root = Path(__file__).resolve().parents[2]
browser = (root/'engine/app/src/main/java/com/palworld/vd/BrowserLayer.java').read_text(encoding='utf-8')
activity = (root/'engine/app/src/main/java/com/palworld/vd/MainActivity.java').read_text(encoding='utf-8')
def block(source, marker):
    start=source.index(marker); brace=source.index('{',start); end=brace+1; depth=1
    while depth:
        depth += (source[end]=='{')-(source[end]=='}'); end+=1
    return source[start:end]
ensure=block(browser,'private void ensureWeb(')
unload=block(browser,'private void unloadTab(').replace('android.view.ViewGroup','ViewGroup')
stop=block(activity,'private void stopSilentCollect(').replace('android.view.ViewGroup','ViewGroup')
start=block(activity,'private void startSilentCollect(')
prefix=start[:start.index('        // 抖音')]
assert 'if (collectRuns.get(run.cid) != run) return;' in activity
creation=block(activity,'protected void onCreate(')
assert 'ensureBrowser()' not in creation[:creation.index('web = new WebView(this)')], 'cold intent must wait for main page initialization'
assert 'pendingBrowserUrl = getIntent().getStringExtra("url")' in creation
assert 'public void onPageFinished(WebView view, String url)' in creation
# Browser creation occurs in the page-finished callback, after the root and bridge exist.
assert creation.index('web.setWebViewClient(') < creation.index('ensureBrowser().show(target)')

java='''
import java.util.*;
class Optimization {
 static class WebView {Object tag;boolean destroyed;String getTitle(){return "Retained title";}Object getParent(){return null;}void destroy(){destroyed=true;}void setTag(Object t){tag=t;}}
 static class ViewGroup {void removeView(Object w){}}
 static class Handler {void removeCallbacksAndMessages(Object o){}}
 static class Tab {WebView w;long lastUsed;int generation;boolean loaded;String title;Map<String,String> resources=new HashMap<>();Tab(long n){lastUsed=n;}}
 static class CollectRun {WebView web=new WebView();Handler h=new Handler();}
 List<Tab> tabs=new ArrayList<>();static final int TAB_MAX=2,COLLECT_MAX=1;
 Map<String,CollectRun> collectRuns=new HashMap<>();
 Map<String,String[]> collectQueue=new LinkedHashMap<>();
 List<String> launched=new ArrayList<>();boolean destroyed;
 boolean isDestroyed(){return destroyed;}
 WebView buildWeb(){return new WebView();}
''' + unload + ensure + prefix + '''
 collectRuns.put(cid,new CollectRun());launched.add(cid);
 }
''' + stop + '''
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 void checkResidents(){check(tabs.stream().filter(t->t.w!=null).count()<=2);}
 public static void main(String[] args){
  Optimization test=new Optimization();
  for(int i=0;i<6;i++){Tab t=new Tab(i);test.tabs.add(t);test.ensureWeb(t);test.checkResidents();}
  check(test.tabs.get(0).title.equals("Retained title"));
  for(int round=0;round<3;round++)for(Tab t:test.tabs){t.lastUsed+=100;test.ensureWeb(t);test.checkResidents();}
  test.startSilentCollect("a","any","a");test.startSilentCollect("b","any","b");test.startSilentCollect("c","any","c");
  test.startSilentCollect("c","any","c");check(test.collectRuns.size()==1 && test.collectQueue.size()==2);
  test.stopSilentCollect("b");check(test.collectQueue.size()==1 && test.launched.size()==1);
  test.stopSilentCollect("a");check(test.launched.equals(Arrays.asList("a","c")) && test.collectRuns.containsKey("c"));
  test.stopSilentCollect("c");check(test.collectRuns.isEmpty());
  test.startSilentCollect("d","any","d");test.startSilentCollect("e","any","e");
  test.destroyed=true;test.collectQueue.clear();test.stopSilentCollect("d");check(test.collectRuns.isEmpty());
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
    source=Path(directory)/'Optimization.java';source.write_text(java,encoding='utf-8')
    subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(source)],check=True)
    subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'Optimization'],check=True)
print('Resident page limit, revisits, FIFO extraction, queued/active cancellation and teardown passed')
