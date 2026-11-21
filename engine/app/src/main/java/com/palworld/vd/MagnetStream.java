package com.palworld.vd;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** aria2 RPC observation plus a loopback-only, verified-piece HTTP source. */
final class MagnetStream {
    private static final ConcurrentHashMap<String, MagnetStream> sessions = new ConcurrentHashMap<>();
    static MagnetStream get(String id) { return id == null ? null : sessions.get(id); }
    static void cancel(String id) { MagnetStream s = sessions.remove(id); if (s != null) s.fail("下载已取消"); }
    final int rpcPort;
    final String secret = UUID.randomUUID().toString();
    private final String token = UUID.randomUUID().toString();
    private final File root;
    private volatile JSONObject snapshot = new JSONObject();
    private volatile List<Entry> entries = Collections.emptyList();
    private volatile String gid = "", error = "";
    private volatile boolean finished;
    private volatile long lastAccess = System.currentTimeMillis();
    private volatile ServerSocket server;
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService http = new ThreadPoolExecutor(0, 4, 15, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "BT-range"); t.setDaemon(true); return t; });
    private volatile int viewers;
    private int priorityHead = 8;

    static final class Entry {
        final int index; final long offset, length; final String name;
        volatile File file;
        Entry(int i, long o, long l, File f) { index=i; offset=o; length=l; file=f; name=f.getName(); }
        boolean video() { return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|m4v|avi|ts|m2ts|flv|mpg|mpeg)$"); }
    }

    MagnetStream(String id, File directory) throws IOException {
        root = directory.getCanonicalFile();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { rpcPort = socket.getLocalPort(); }
        // Completed sessions have no download work; do not retain them indefinitely.
        for (Map.Entry<String, MagnetStream> e : sessions.entrySet()) {
            MagnetStream old = e.getValue();
            if (old.finished && old.viewers == 0 && (sessions.size() >= 12 || System.currentTimeMillis()-old.lastAccess > 1800000)) {
                if (sessions.remove(e.getKey(), old)) old.stopServing();
            }
        }
        sessions.put(id, this);
    }

    void options(List<String> args) {
        args.add("--enable-rpc=true"); args.add("--rpc-listen-all=false");
        args.add("--rpc-listen-port=" + rpcPort); args.add("--rpc-secret=" + secret);
        args.add("--bt-prioritize-piece=head=8M,tail=4M");
    }

    void observe(Process process) {
        Thread t = new Thread(() -> {
            while (!finished) {
                try {
                    refresh();
                    // RPC mode stays alive after the last download. Shut down gracefully so
                    // LocalEngine can verify success and commit files instead of sticking at 99%.
                    if ("complete".equals(snapshot.optString("status"))) {
                        rpc("shutdown", new JSONArray());
                        break;
                    }
                } catch (Exception ignored) { }
                try { process.exitValue(); break; } catch (IllegalThreadStateException running) { }
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        }, "BT-pieces");
        t.setDaemon(true); t.start();
    }

    private Object rpc(String method, JSONArray params) throws Exception {
        params.put(0, "token:" + secret);
        JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", "vd").put("method", "aria2."+method).put("params", params);
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:"+rpcPort+"/jsonrpc").openConnection();
        c.setConnectTimeout(800); c.setReadTimeout(1500); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { if (bytes.size()+n > 8388608) throw new IOException("元数据过大"); bytes.write(buf,0,n); }
            }
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            if (response.has("error")) throw new IOException("BT 控制接口暂不可用");
            return response.get("result");
        } finally { c.disconnect(); }
    }

    private void refresh() throws Exception {
        JSONArray active = (JSONArray) rpc("tellActive", new JSONArray());
        JSONObject found = null;
        for (int i=0; i<active.length(); i++) {
            JSONObject s = active.getJSONObject(i);
            JSONArray f = s.optJSONArray("files");
            if (f != null && f.length()>0 && !f.getJSONObject(0).optString("path").contains("[METADATA]")) { found=s; break; }
        }
        if (found == null && !gid.isEmpty()) found=(JSONObject)rpc("tellStatus", new JSONArray().put(1,gid));
        if (found == null || finished) return;
        JSONArray files = found.optJSONArray("files");
        if (files == null) return;
        ArrayList<Entry> next = new ArrayList<>(); long offset=0;
        for (int i=0; i<files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            long length = f.optLong("length");
            File path = new File(f.optString("path")).getCanonicalFile();
            if (length < 0 || !path.getPath().startsWith(root.getPath()+File.separator)) throw new IOException("种子文件路径无效");
            next.add(new Entry(f.optInt("index"),offset,length,path));
            if (offset > Long.MAX_VALUE-length) throw new IOException("种子大小无效");
            offset+=length;
        }
        gid=found.optString("gid");
        // Keep Entry identity stable: active HTTP reads must see the final renamed file too.
        if (entries.isEmpty()) entries=Collections.unmodifiableList(next);
        snapshot=found;
    }

    List<Entry> videos() { lastAccess=System.currentTimeMillis(); ArrayList<Entry> out=new ArrayList<>(); for(Entry e:entries) if(e.video() && e.length>0) out.add(e); return out; }
    boolean hasMetadata() { return !entries.isEmpty(); }
    private Entry entry(int index) { for(Entry e:entries) if(e.index==index) return e; return null; }
    String note() {
        if (!error.isEmpty()) return error;
        if (finished) return "已下载完成 · 本地播放";
        JSONObject s=snapshot;
        long total=s.optLong("totalLength"), done=s.optLong("completedLength"), speed=s.optLong("downloadSpeed");
        if (entries.isEmpty()) return "正在获取磁力文件列表…可返回首页，下载继续进行";
        return String.format(Locale.CHINA,"%s / %s · %s/s · %d 个节点", size(done),size(total),size(speed),s.optInt("connections"));
    }
    static String size(long n) {
        if(n<1024) return Math.max(0,n)+" B";
        if(n<1048576) return String.format(Locale.CHINA,"%.1f KB",n/1024.0);
        return n>=1073741824L ? String.format(Locale.CHINA,"%.2f GB",n/1073741824.0) : String.format(Locale.CHINA,"%.1f MB",n/1048576.0);
    }
    boolean failed() { return !error.isEmpty(); }

    synchronized void moved(File from, File to) { for(Entry e:entries) if(e.file.equals(from)) e.file=to; }
    void complete() { finished=true; }
    void stopped() { if(!finished) fail("下载已停止，可返回下载页查看原因或重试"); }
    void fail(String message) { error=message; finished=true; stopServing(); }
    void attach() { viewers++; }
    synchronized void detach() { viewers=Math.max(0,viewers-1); if(viewers==0) stopServing(); }
    synchronized void stopServing() {
        ServerSocket old=server; server=null;
        if(old!=null) try { old.close(); } catch(IOException ignored) { }
        for(Socket client:clients) try { client.close(); } catch(IOException ignored) { }
    }

    synchronized String url(int index) throws IOException {
        Entry e=entry(index);
        if(e==null || !e.video() || failed()) throw new IOException("视频暂不可用");
        if(server==null) {
            final ServerSocket socket=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1")); server=socket;
            Thread listener=new Thread(() -> {
                while(!socket.isClosed()) {
                    try {
                        Socket client=socket.accept(); clients.add(client);
                        try { http.execute(() -> serve(client)); }
                        catch(RejectedExecutionException busy) { clients.remove(client); client.close(); }
                    } catch(IOException closed) { break; }
                }
            },"BT-loopback"); listener.setDaemon(true); listener.start();
        }
        return "http://127.0.0.1:"+server.getLocalPort()+"/"+token+"/"+index+"/"+URLEncoder.encode(e.name,"UTF-8");
    }

    /** Only head/tail priority is supported by aria2. Expanding head helps forward seeks,
     * but does not promise arbitrary-piece deadline scheduling. */
    private void prioritize(Entry e, long position) {
        if(finished) return;
        int mb=(int)Math.min(2048,Math.max(8,(position+8388608L+1048575)/1048576));
        synchronized(this) { if(mb<=priorityHead) return; priorityHead=mb; }
        try { rpc("changeOption",new JSONArray().put(1,gid).put(2,new JSONObject().put("bt-prioritize-piece","head="+mb+"M,tail=4M"))); }
        catch(Exception ignored) { }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
        while((c=in.read())!=-1 && c!='\n') { if(b.size()>=8192) throw new IOException("请求过长"); if(c!='\r') b.write(c); }
        return b.toString("US-ASCII");
    }
    private static void header(OutputStream out,String status,String extra) throws IOException {
        out.write(("HTTP/1.1 "+status+"\r\nConnection: close\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n"+extra+"\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    private void serve(Socket client) {
        try(Socket socket=client) {
            socket.setSoTimeout(5000);
            InputStream in=socket.getInputStream(); OutputStream out=socket.getOutputStream();
            String[] request=line(in).split(" "); String range=null;
            if(request.length!=3 || !(request[0].equals("GET") || request[0].equals("HEAD"))) { header(out,"405 Method Not Allowed","Content-Length: 0\r\n"); return; }
            for(int i=0;i<64;i++) { String h=line(in); if(h.isEmpty()) break; if(i==63) throw new IOException("请求过长"); if(h.toLowerCase(Locale.ROOT).startsWith("range:")) range=h.substring(6).trim(); }
            String[] path=request[1].split("/",5);
            if(path.length<4 || !path[1].equals(token)) { header(out,"404 Not Found","Content-Length: 0\r\n"); return; }
            Entry e=entry(Integer.parseInt(path[2]));
            if(e==null || !e.video() || failed()) { header(out,"410 Gone","Content-Length: 0\r\n"); return; }
            long[] r;
            try { r=TorrentPieces.range(range,e.length); }
            catch(IllegalArgumentException invalid) { header(out,"416 Range Not Satisfiable","Content-Length: 0\r\nContent-Range: bytes */"+e.length+"\r\n"); return; }
            String mime=e.name.toLowerCase(Locale.ROOT).endsWith(".mkv")?"video/x-matroska":e.name.toLowerCase(Locale.ROOT).endsWith(".webm")?"video/webm":"video/mp4";
            header(out,range==null?"200 OK":"206 Partial Content","Content-Type: "+mime+"\r\nContent-Length: "+(r[1]-r[0]+1)+"\r\n"+(range==null?"":"Content-Range: bytes "+r[0]+"-"+r[1]+"/"+e.length+"\r\n"));
            if(request[0].equals("HEAD")) return;
            socket.setSoTimeout(1);
            prioritize(e,r[0]);
            byte[] bytes=new byte[262144]; long pos=r[0], waited=System.currentTimeMillis();
            while(pos<=r[1] && !failed() && !socket.isClosed()) {
                JSONObject s=snapshot;
                long available=finished?e.length-pos:TorrentPieces.available(s.optString("bitfield"),s.optLong("pieceLength"),e.offset,pos,e.length);
                if(available<=0 || !e.file.isFile()) {
                    if(System.currentTimeMillis()-waited>90000) throw new IOException("等待片段超时");
                    // A seek closes the old HTTP request. Detect that even when no piece
                    // is available, otherwise abandoned waits occupy all four workers.
                    try { if(in.read()==-1) break; else throw new IOException("意外请求内容"); }
                    catch(SocketTimeoutException waiting) { }
                    Thread.sleep(150); continue;
                }
                int count=(int)Math.min(Math.min(available,r[1]-pos+1),bytes.length), n;
                // Close each bounded read before commit: MuMu shared folders can deny
                // rename while a descriptor is held open. The same lock protects relocation.
                synchronized(this) {
                    try(RandomAccessFile media = new RandomAccessFile(e.file,"r")) {
                        media.seek(pos); n=media.read(bytes,0,count);
                    }
                }
                if(n<=0) throw new EOFException();
                out.write(bytes,0,n); out.flush(); pos+=n; waited=System.currentTimeMillis();
            }
        } catch(Exception ignored) { /* Client seeks/back, cancellation, or bounded buffer timeout. */ }
        finally { clients.remove(client); }
    }
}
