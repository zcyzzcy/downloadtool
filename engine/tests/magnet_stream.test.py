"""Compile the production loopback source and exercise real HTTP/RPC requests on JVM."""
from pathlib import Path
import subprocess, shutil, tempfile, urllib.request, os
root=Path(__file__).resolve().parents[2]
cache=root/'build/test-deps/json-20240303.jar'
cache.parent.mkdir(exist_ok=True,parents=True)
if not cache.exists():
    existing=root/'build/review-20261112/v169/json.jar'
    if existing.exists(): shutil.copyfile(existing,cache)
    else: urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',cache)
source=r"""
package com.palworld.vd;
import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;import org.json.*;
public class MagnetStreamCheck {
 static volatile JSONObject status; static int priorities; static volatile boolean shutdown;
 static void check(boolean b,String what){if(!b)throw new AssertionError(what);}
 static void refresh(MagnetStream s)throws Exception{java.lang.reflect.Method m=MagnetStream.class.getDeclaredMethod("refresh");m.setAccessible(true);m.invoke(s);}
 static HttpURLConnection connection(String url,String range)throws Exception{
   HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setReadTimeout(5000);c.setConnectTimeout(2000);
   if(range!=null)c.setRequestProperty("Range",range);return c;
 }
 static byte[] read(String url,String range)throws Exception{HttpURLConnection c=connection(url,range);try(InputStream in=c.getInputStream()){return in.readAllBytes();}finally{c.disconnect();}}
 static JSONObject file(int i,Path p,long length)throws Exception{return new JSONObject().put("index",i).put("path",p.toString()).put("length",String.valueOf(length)).put("selected","true");}
 public static void main(String[] ignored)throws Exception{
   check(TorrentPieces.has("a0",0)&&!TorrentPieces.has("a0",1)&&TorrentPieces.has("a0",2),"MSB bitfield order");
   check(!TorrentPieces.has("g",0)&&!TorrentPieces.has("f",4),"invalid/out of bounds bitfield");
   check(TorrentPieces.available("8",4,3,0,13)==1,"shared piece/file offset");
   check(TorrentPieces.available("c",4,3,1,13)==4,"offset into second piece");
   check(TorrentPieces.available("f",4,3,12,13)==1,"final file byte");
   check(TorrentPieces.available("",0,0,0,4)==0,"unknown metadata is never available");
   check(Arrays.equals(TorrentPieces.range("bytes=4294967296-",5368709120L),new long[]{4294967296L,5368709119L}),">4 GB range");
   check(Arrays.equals(TorrentPieces.range("bytes=-4",10),new long[]{6,9}),"suffix range");
   for(String r:new String[]{"bytes=10-","bytes=8-4","bytes=-0","bytes=1-2,4-5","foo=1-2","bytes=9223372036854775808-"}){
     try{TorrentPieces.range(r,10);throw new AssertionError("accepted invalid range "+r);}catch(IllegalArgumentException expected){}
   }
   Path root=Files.createTempDirectory("vd-range-");Path first=root.resolve("readme.txt"),video=root.resolve("测试.mp4");
   Files.write(first,new byte[]{1,2,3});byte[] expected=new byte[13];for(int i=0;i<13;i++)expected[i]=(byte)(i+31);Files.write(video,expected);
   MagnetStream s=new MagnetStream("test",root.toFile());
   status=new JSONObject().put("gid","data").put("totalLength","16").put("completedLength","4").put("downloadSpeed","1234").put("connections","2")
     .put("pieceLength","4").put("bitfield","8").put("files",new JSONArray().put(file(1,first,3)).put(file(2,video,13)));
   HttpServer rpc=HttpServer.create(new InetSocketAddress("127.0.0.1",s.rpcPort),0);
   rpc.createContext("/jsonrpc",ex->{try{
     JSONObject request=new JSONObject(new String(ex.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
     check(request.getJSONArray("params").getString(0).equals("token:"+s.secret),"RPC authorization");
     Object result;String method=request.getString("method");
     if(method.equals("aria2.tellActive"))result=new JSONArray().put(status);
     else if(method.equals("aria2.changeOption")){priorities++;result="OK";}
     else if(method.equals("aria2.shutdown")){shutdown=true;result="OK";}
     else result=status;
     byte[] body=new JSONObject().put("result",result).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
     ex.sendResponseHeaders(200,body.length);ex.getResponseBody().write(body);ex.close();
   }catch(Exception e){throw new RuntimeException(e);}});rpc.start();
   ExecutorService pool=Executors.newCachedThreadPool();
   try{
     refresh(s);check(s.videos().size()==1&&s.videos().get(0).index==2,"video filtering, original torrent index");
     check(s.note().contains("2 个节点"),"cached status metrics");s.attach();String url=s.url(2);
     HttpURLConnection head=connection(url,"bytes=2-5");head.setRequestMethod("HEAD");check(head.getResponseCode()==206&&head.getContentLengthLong()==4,"HEAD and partial content");head.disconnect();
     HttpURLConnection invalid=connection(url,"bytes=99-");check(invalid.getResponseCode()==416&&invalid.getHeaderField("Content-Range").equals("bytes */13"),"HTTP 416");invalid.disconnect();
     String bad=url.replaceFirst("/[a-f0-9-]{36}/","/unauthorized/");HttpURLConnection unauthorized=connection(bad,null);check(unauthorized.getResponseCode()==404,"unguessable capability token");unauthorized.disconnect();
     URI u=URI.create(url);
     for(int i=0;i<8;i++){
       try(Socket abandoned=new Socket("127.0.0.1",u.getPort())){
         abandoned.getOutputStream().write(("GET "+u.getRawPath()+" HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=8-9\r\n\r\n").getBytes());
       }
       Thread.sleep(55);
     }
     Thread.sleep(400);check(Arrays.equals(read(url,"bytes=0-0"),new byte[]{expected[0]}),"abandoned seek requests release workers promptly");
     Future<byte[]> waiting=pool.submit(()->read(url,"bytes=0-4"));Thread.sleep(400);check(!waiting.isDone(),"sparse file bytes must not be served");
     status.put("bitfield","c");refresh(s);check(Arrays.equals(waiting.get(3,TimeUnit.SECONDS),Arrays.copyOfRange(expected,0,5)),"verified bytes unblock after piece arrives");
     HttpURLConnection partial=connection(url,"bytes=1-12");InputStream opened=partial.getInputStream();byte[] prefix=opened.readNBytes(4);check(Arrays.equals(prefix,Arrays.copyOfRange(expected,1,5)),"partial playback before completion");
     check(priorities>0,"forward-read head priority update reached RPC");
     // Refresh must preserve Entry identity held by active readers.
     refresh(s);Path finalFile=root.resolve("完成.mp4");synchronized(s){Files.move(video,finalFile);s.moved(video.toFile(),finalFile.toFile());}s.complete();
     check(Arrays.equals(opened.readAllBytes(),Arrays.copyOfRange(expected,5,13)),"commit while playing keeps open file valid");opened.close();partial.disconnect();
     check(Arrays.equals(read(url,null),expected),"new request follows final filename");
     s.detach();s.attach();String reopened=s.url(2);check(Arrays.equals(read(reopened,"bytes=-3"),Arrays.copyOfRange(expected,10,13)),"server reopens after viewer leaves");s.detach();
     MagnetStream blocked=new MagnetStream("blocked",root.toFile());
     // Copy a real, verified metadata snapshot into another session through its RPC port.
     java.lang.reflect.Field ef=MagnetStream.class.getDeclaredField("entries");ef.setAccessible(true);ef.set(blocked,s.videos());
     String blockedUrl=blocked.url(2);Future<byte[]> cancelled=pool.submit(()->{try{return read(blockedUrl,"bytes=0-3");}catch(IOException e){return new byte[0];}});
     Thread.sleep(250);check(!cancelled.isDone(),"missing pieces wait");MagnetStream.cancel("blocked");check(cancelled.get(3,TimeUnit.SECONDS).length==0&&blocked.failed(),"cancel promptly closes pending reads");
     status.put("status","complete").put("files",new JSONArray().put(file(1,first,3)).put(file(2,finalFile,13)));
     // Exercise process completion after the HTTP rename/reopen checks.
     java.lang.reflect.Field done=MagnetStream.class.getDeclaredField("finished");done.setAccessible(true);done.setBoolean(s,false);
     s.observe(new Process(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}public InputStream getInputStream(){return InputStream.nullInputStream();}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){throw new IllegalThreadStateException();}public void destroy(){}});
     long deadline=System.currentTimeMillis()+2500;while(!shutdown&&System.currentTimeMillis()<deadline)Thread.sleep(50);
     check(shutdown,"RPC process exits gracefully after completed download");
     System.out.println("Verified-piece HTTP playback: multi-file offsets, >4GB ranges, sparse gating, piece arrival, seek priority, rename during playback, reopen, authorization and cancellation passed");
   }finally{s.fail("test ended");rpc.stop(0);pool.shutdownNow();Thread.sleep(350);for(Path p:Files.newDirectoryStream(root))Files.delete(p);Files.delete(root);}
 }
}
"""
with tempfile.TemporaryDirectory() as d:
    base=Path(d);test=base/'MagnetStreamCheck.java';test.write_text(source,encoding='utf-8')
    production=root/'engine/app/src/main/java/com/palworld/vd'
    javac=shutil.which('javac') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\javac.exe'
    java=shutil.which('java') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\java.exe'
    subprocess.run([javac,'-J-Xmx128m','-encoding','UTF-8','-cp',str(cache),'-d',d,str(production/'TorrentPieces.java'),str(production/'MagnetStream.java'),str(test)],check=True)
    subprocess.run([java,'-Xmx128m','-cp',d+os.pathsep+str(cache),'com.palworld.vd.MagnetStreamCheck'],check=True,timeout=30)
