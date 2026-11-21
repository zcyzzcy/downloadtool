"""Exercise queued thumbnail deduplication using the production scheduling method."""
from pathlib import Path
import shutil,subprocess,tempfile
source=Path('engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
start=source.index('    public void ensureThumbs(');end=source.index('\n    }',start)+6
method=source[start:end]
java='''
import java.io.*;import java.util.*;import java.nio.file.*;import java.util.concurrent.*;
class ThumbnailCheck {
 static Set<String> thumbQueued=ConcurrentHashMap.newKeySet();
 static class Executor {List<Runnable> queue=new ArrayList<>();boolean reject;void submit(Runnable r){if(reject)throw new RejectedExecutionException();queue.add(r);}}
 static Executor thumbExec=new Executor();File vdDir;int generated;
 boolean isVideoName(String n){return n.endsWith(".mp4");}
 boolean validThumb(File d,String n){return new File(new File(d,".thumbs"),n+".jpg.stamp").exists();}
 void genThumb(File d,String n){generated++;}
''' + method + '''
 public static void main(String[] args)throws Exception {
  ThumbnailCheck t=new ThumbnailCheck();t.vdDir=new File(args[0]);
  t.ensureThumbs(Arrays.asList("a.mp4","a.mp4","b.mp4","../bad.mp4","image.jpg"));
  t.ensureThumbs(Arrays.asList("a.mp4","b.mp4"));
  if(thumbExec.queue.size()!=2 || thumbQueued.size()!=2)throw new AssertionError();
  for(Runnable job:thumbExec.queue)job.run();thumbExec.queue.clear();
  if(t.generated!=2 || !thumbQueued.isEmpty())throw new AssertionError();
  File thumbs=new File(t.vdDir,".thumbs");thumbs.mkdirs();Files.write(new File(thumbs,"a.mp4.jpg").toPath(),new byte[1600]);
  t.ensureThumbs(Arrays.asList("a.mp4"));if(thumbExec.queue.size()!=1)throw new AssertionError("legacy cache reused");
  for(Runnable job:thumbExec.queue)job.run();thumbExec.queue.clear();
  new File(thumbs,"a.mp4.jpg.stamp").createNewFile();
  t.ensureThumbs(Arrays.asList("a.mp4"));if(!thumbExec.queue.isEmpty())throw new AssertionError();
  thumbExec.reject=true;t.ensureThumbs(Arrays.asList("c.mp4"));if(!thumbQueued.isEmpty())throw new AssertionError();
  thumbExec.reject=false;t.ensureThumbs(Arrays.asList("c.mp4"));if(thumbExec.queue.size()!=1)throw new AssertionError();
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
 path=Path(directory);(path/'ThumbnailCheck.java').write_text(java,encoding='utf-8');(path/'media').mkdir()
 subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(path/'ThumbnailCheck.java')],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-XX:ReservedCodeCacheSize=32m','-cp',directory,'ThumbnailCheck',str(path/'media')],check=True)
print('Pending thumbnail deduplication, completed-file skip and rejected-job recovery passed')
