"""Exercise the production durable-history bridge with a controllable preference store."""
from pathlib import Path
import tempfile, subprocess, shutil, os

base = Path('engine/app/src/main/java/com/palworld/vd')
s = (base / 'MainActivity.java').read_text(encoding='utf-8')


def block(marker):
    a = s.index(marker)
    i = s.index('{', a)
    b, depth = i + 1, 1
    while depth:
        depth += (s[b] == '{') - (s[b] == '}')
        b += 1
    return s[a:b].replace('@JavascriptInterface ', '')


methods = '\n'.join(block(x) for x in [
    '@JavascriptInterface public String taskRecords(',
    '@JavascriptInterface public boolean saveTaskRecords('])
java = r'''
class HistoryBridgeCheck {
 static final int MODE_PRIVATE=0;
 static class Prefs {
  String value="";int commits;boolean succeed=true;
  String getString(String k,String fallback){return value.isEmpty()?fallback:value;}
  Prefs edit(){return this;}Prefs putString(String k,String v){value=v;return this;}
  boolean commit(){commits++;return succeed;}
 }
 final Prefs prefs=new Prefs();Prefs getSharedPreferences(String n,int mode){return prefs;}
''' + methods + r'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  HistoryBridgeCheck h=new HistoryBridgeCheck();check(h.taskRecords().equals(""));
  check(h.saveTaskRecords("[]"));check(h.prefs.commits==1);check(h.taskRecords().equals("[]"));
  check(!h.saveTaskRecords(null)&&!h.saveTaskRecords("{}")&&!h.saveTaskRecords("broken"));check(h.prefs.commits==1);
  check(!h.saveTaskRecords(" ".repeat(4*1024*1024+1)));check(h.prefs.commits==1);
  h.prefs.succeed=false;check(!h.saveTaskRecords("[]"));check(h.prefs.commits==2);
  System.out.println("Native history commits synchronously, accepts empty history, rejects malformed/oversize data and reports disk failure");
 }
}
'''
jar = Path('build/test-deps/json-20240303.jar').resolve()
with tempfile.TemporaryDirectory() as d:
    f = Path(d) / 'HistoryBridgeCheck.java'
    f.write_text(java, encoding='utf-8')
    javac = shutil.which('javac') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\javac.exe'
    jvm = shutil.which('java') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\java.exe'
    subprocess.run([javac, '-J-Xmx128m', '-encoding', 'UTF-8', '-cp', str(jar), str(f)], check=True)
    subprocess.run([jvm, '-Xmx128m', '-cp', d + os.pathsep + str(jar), 'HistoryBridgeCheck'], check=True)
