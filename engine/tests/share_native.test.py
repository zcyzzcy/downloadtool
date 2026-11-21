"""Check production native share persistence, acknowledgement and rich-text link retention."""
from pathlib import Path
import subprocess,tempfile,shutil
s=Path('engine/app/src/main/java/com/palworld/vd/MainActivity.java').read_text(encoding='utf-8')
def block(marker):
 start=s.index(marker);end=s.index('{',start)+1;depth=1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
methods='\n'.join(block(m) for m in ['private org.json.JSONArray sharedTexts(', 'private synchronized String peekSharedText(', 'private synchronized void acknowledgeSharedText(', 'private synchronized void enqueueSharedText(', 'private static void appendShareText('])
java='''
import java.util.*;
class org {static class json {static class JSONArray {
 static Map<String,List<String>> snapshots=new HashMap<>();List<String> values;
 JSONArray(){values=new ArrayList<>();}JSONArray(String text){values=new ArrayList<>(snapshots.getOrDefault(text,new ArrayList<>()));}
 int length(){return values.size();}void put(String s){values.add(s);}void remove(int i){values.remove(i);}
 String optString(int i,String fallback){return i<values.size()?values.get(i):fallback;}String optString(int i){return optString(i,"");}
 public String toString(){String key="snapshot-"+snapshots.size();snapshots.put(key,new ArrayList<>(values));return key;}
}}}
class android {static class text {interface Spanned extends CharSequence {<T>T[] getSpans(int a,int b,Class<T> type);}static class style {static class URLSpan {String url;URLSpan(String u){url=u;}String getURL(){return url;}}}}}
class ShareNativeCheck {
 static final String PENDING_SHARES="shares";Prefs prefs;Web web;
 boolean isDestroyed(){return false;}static class Web {void post(Runnable r){r.run();}void evaluateJavascript(String js,Object callback){}}
 static class Prefs {String value="[]";String getString(String key,String fallback){return value;}Editor edit(){return new Editor();}class Editor {Editor putString(String key,String v){value=v;return this;}void apply(){}}}
 ShareNativeCheck(Prefs p){prefs=p;}
''' + methods + '''
 static class Rich implements android.text.Spanned {
  public int length(){return 5;}public char charAt(int i){return "Title".charAt(i);}public CharSequence subSequence(int a,int b){return "Title".subSequence(a,b);}public String toString(){return "Title";}
  @SuppressWarnings("unchecked") public <T>T[] getSpans(int a,int b,Class<T> type){return (T[])new android.text.style.URLSpan[]{new android.text.style.URLSpan("https://example.test/video?a=1&b=2")};}
 }
 public static void main(String[] args){
  Prefs prefs=new Prefs();ShareNativeCheck first=new ShareNativeCheck(prefs);
  first.enqueueSharedText("A");first.enqueueSharedText("B");first.enqueueSharedText(" ");
  first.acknowledgeSharedText("B");if(!first.peekSharedText().equals("A"))throw new AssertionError();
  ShareNativeCheck recreated=new ShareNativeCheck(prefs);if(!recreated.peekSharedText().equals("A"))throw new AssertionError();
  recreated.acknowledgeSharedText("A");recreated.enqueueSharedText("C");if(!recreated.peekSharedText().equals("B"))throw new AssertionError();
  recreated.acknowledgeSharedText("B");if(!recreated.peekSharedText().equals("C"))throw new AssertionError();recreated.acknowledgeSharedText("C");if(!recreated.peekSharedText().isEmpty())throw new AssertionError();
  StringBuilder rich=new StringBuilder();appendShareText(rich,new Rich());if(!rich.toString().equals("Title\\nhttps://example.test/video?a=1&b=2"))throw new AssertionError();
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory)/'ShareNativeCheck.java';p.write_text(java,encoding='utf-8')
 subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(p)],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'ShareNativeCheck'],check=True)
print('Native share persistence, ordered acknowledgement and rich-text URL retention passed')
