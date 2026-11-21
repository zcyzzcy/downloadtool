"""Exercise production cache identity and atomic publication using real files."""
from pathlib import Path
import subprocess, tempfile, shutil
source=Path('engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
def block(marker):
 start=source.index(marker);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
methods='\n'.join(block(m) for m in ['private static String thumbStamp(', 'public static boolean validThumb(', 'private static void invalidateThumb(', 'private static boolean publishThumb('])
methods=methods.replace('android.system.Os.stat(source.getAbsolutePath()).st_ino', 'java.nio.file.Files.getAttribute(source.toPath(), "lastModifiedTime").hashCode()')
java='''import java.io.*;import java.nio.file.*;
class IdentityTest {
 static final Object FILE_COMMIT_LOCK=new Object();
'''+methods+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  File dir=new File(args[0]);new File(dir,".thumbs").mkdirs();
  File video=new File(dir,"download.mp4");Files.write(video.toPath(),new byte[20]);
  File cached=new File(dir,".thumbs/download.mp4.jpg");Files.write(cached.toPath(),new byte[1600]);
  check(!validThumb(dir,video.getName()));
  String old=thumbStamp(video);
  File frame=new File(dir,"frame.jpg");Files.write(frame.toPath(),new byte[]{1,2,3});
  check(publishThumb(dir,video.getName(),old,frame));check(validThumb(dir,video.getName()));
  Files.write(video.toPath(),new byte[21]);check(!validThumb(dir,video.getName()));
  File late=new File(dir,"late.jpg");Files.write(late.toPath(),new byte[]{4,5,6});
  check(!publishThumb(dir,video.getName(),old,late));
  check(publishThumb(dir,video.getName(),thumbStamp(video),late));check(validThumb(dir,video.getName()));
  check(Files.readAllBytes(cached.toPath())[0]==4);
  invalidateThumb(dir,video.getName());check(!cached.exists());check(!new File(cached.getPath()+".stamp").exists());
  video.delete();check(!publishThumb(dir,video.getName(),old,cached));
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory);(p/'IdentityTest.java').write_text(java,encoding='utf-8');(p/'media').mkdir()
 subprocess.run([shutil.which('javac'),'-J-Xmx128m',str(p/'IdentityTest.java')],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-cp',directory,'IdentityTest',str(p/'media')],check=True)
html=Path('engine/app/src/main/assets/index.html').read_text(encoding='utf-8')
assert 'claimNewestFile' not in html
assert 'const norm = s =>' not in html[html.index('function healDoneTasks'):html.index('function healDoneTasks')+4000]
print('Legacy cache invalidation, same-name replacement, stale publication, deletion and association isolation passed')
