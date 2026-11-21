"""Run pure-Java regressions using the app's source."""
from pathlib import Path
import re
import subprocess
import tempfile
import shutil

root = Path(__file__).resolve().parents[2]
java_dir = root / "engine/app/src/main/java/com/palworld/vd"
zip_source = (java_dir / "ZipMergeActivity.java").read_text(encoding="utf-8")
start = zip_source.index("    private static int appendBase(")
end = zip_source.index("\n    }", start) + len("\n    }")
append_method = zip_source[start:end]
engine_source = (java_dir / "LocalEngine.java").read_text(encoding="utf-8")
start = engine_source.index("    private static boolean moveOwnedFiles(")
end = engine_source.index("\n    }", start) + len("\n    }")
move_method = engine_source[start:end]
commit_methods = []
for signature in ["    private static String uniqueName(", "    private static String commitFile(", "    private static void tcEnter(", "    private static boolean isDirectMediaUrl("]:
    start = engine_source.index(signature)
    end = engine_source.index("\n    }", start) + len("\n    }")
    commit_methods.append(engine_source[start:end].replace("invalidateThumb(directory, target);", ""))
gallery_methods = []
for signature in ["    private boolean galleryTaken(", "    private List<String> downloadMediaUrls(", "    private static void deleteRecursive("]:
    start = engine_source.index(signature)
    end = engine_source.index("\n    }", start) + len("\n    }")
    gallery_methods.append(engine_source[start:end])
    if signature == "    private List<String> downloadMediaUrls(":
        start = engine_source.index(signature, end)
        end = engine_source.index("\n    }", start) + len("\n    }")
        gallery_methods.append(engine_source[start:end])
harness = """
import java.util.*;
import java.io.*;
import java.nio.file.*;
public class NativeToolsCheck {
    private static final Object FILE_COMMIT_LOCK = new Object();
    private static final java.util.concurrent.Semaphore tcSlots = new java.util.concurrent.Semaphore(2);
    private static final Map<String, File> stagingDirs = new java.util.concurrent.ConcurrentHashMap<>();
    private File vdDir;
    private static String extFromUrl(String url) { return ".png"; }
    private static void dPostProgress(String id, int pct, String text) {}
    private String imageToJpg(String name, String id) { return name; }
    private void downloadTo(String url, File output, String referer, String userAgent) throws IOException {
        if (url.startsWith("fail:")) throw new IOException("Simulated network failure");
        Files.writeString(output.toPath(), url);
    }
APPEND_METHOD
MOVE_METHOD
COMMIT_METHODS
GALLERY_METHODS
    static void checkBase(int expected, String... names) {
        int actual = appendBase(Arrays.asList(names));
        if (actual != expected) throw new AssertionError("Append base " + actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        if (!isDirectMediaUrl("https://cdn.test/file.MP4?token=sample")
            || !isDirectMediaUrl("http://127.0.0.1:8765/auth/video.mp4")
            || !isDirectMediaUrl("https://cdn.test/audio.mp3")
            || isDirectMediaUrl("https://www.bilibili.com/video/BV1234567890?name=file.mp4"))
            throw new AssertionError("Direct media classification changed");
        tcSlots.acquire(2);
        Thread.currentThread().interrupt();
        try {
            tcEnter();
            throw new AssertionError("Interrupted transcode must not enter");
        } catch (InterruptedException expected) {
            if (!Thread.currentThread().isInterrupted() || tcSlots.availablePermits() != 0)
                throw new AssertionError("Transcode semaphore state changed");
        } finally {
            Thread.interrupted();
            tcSlots.release(2);
        }
        File concurrentDest = new File(args[0], "concurrent");
        concurrentDest.mkdirs();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            File source = new File(args[0], "input-" + i);
            Files.writeString(source.toPath(), String.valueOf(i));
            results.add(pool.submit(() -> commitFile(source, concurrentDest, "same.mp4")));
        }
        pool.shutdown();
        Set<String> names = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (java.util.concurrent.Future<String> result : results) {
            String name = result.get();
            if (name == null) throw new AssertionError("Concurrent commit failed");
            names.add(name);
            contents.add(Files.readString(new File(concurrentDest, name).toPath()));
        }
        if (names.size() != 40 || contents.size() != 40) throw new AssertionError("Concurrent download overwrite");
        NativeToolsCheck engine = new NativeToolsCheck();
        engine.vdDir = new File(args[0], "gallery");
        engine.vdDir.mkdirs();
        File original = new File(engine.vdDir, "same.png");
        Files.writeString(original.toPath(), "original");
        List<String> single = engine.downloadMediaUrls("single", Arrays.asList("https://test/one"), "same");
        if (single.size() != 1 || single.get(0).equals("same.png") || !Files.readString(original.toPath()).equals("original"))
            throw new AssertionError("Single-image download overwrote old file");
        java.util.concurrent.ExecutorService galleries = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<List<String>> groupA = galleries.submit(() -> engine.downloadMediaUrls(
            "group-a", Arrays.asList("https://test/a", "https://test/b"), "group"));
        java.util.concurrent.Future<List<String>> groupB = galleries.submit(() -> engine.downloadMediaUrls(
            "group-b", Arrays.asList("https://test/c", "https://test/d"), "group"));
        galleries.shutdown();
        Set<String> galleryNames = new HashSet<>(groupA.get());
        galleryNames.addAll(groupB.get());
        if (galleryNames.size() != 4) throw new AssertionError("Concurrent gallery collision");
        if (!engine.downloadMediaUrls("failed", Arrays.asList("fail:network"), "broken").isEmpty())
            throw new AssertionError("Failed gallery reported success");
        for (File entry : engine.vdDir.listFiles())
            if (entry.getName().startsWith(".staging-")) throw new AssertionError("Gallery staging leaked");
        File old = new File(args[0], "old");
        File want = new File(args[0], "want");
        old.mkdirs(); want.mkdirs();
        File first = new File(old, "one.mp4");
        File second = new File(old, "two.mp4");
        Files.writeString(first.toPath(), "first");
        Files.writeString(second.toPath(), "second");
        File personal = new File(old, "personal.mp4");
        Files.writeString(personal.toPath(), "personal");
        Set<String> own = new HashSet<>(Arrays.asList("one.mp4", "two.mp4"));
        File conflict = new File(want, "one.mp4");
        Files.writeString(conflict.toPath(), "existing");
        if (moveOwnedFiles(old, want, own)) throw new AssertionError("Conflict must fail");
        if (!Files.readString(conflict.toPath()).equals("existing") || !first.isFile() || !second.isFile())
            throw new AssertionError("Conflicting migration changed files");
        conflict.delete();
        File failing = new File(old, "two.mp4") {
            @Override public boolean renameTo(File dest) { return false; }
        };
        File failingDirectory = new File(old.getPath()) {
            @Override public File[] listFiles() { return new File[]{first, failing}; }
        };
        if (moveOwnedFiles(failingDirectory, want, own)) throw new AssertionError("Failed move must be reported");
        if (!first.isFile() || !second.isFile() || new File(want, "one.mp4").exists())
            throw new AssertionError("Partial migration was not rolled back");
        if (!moveOwnedFiles(old, want, own)) throw new AssertionError("Migration failed");
        if (first.exists() || second.exists() || !personal.isFile()) throw new AssertionError("Ownership changed");
        if (!Files.readString(new File(want, "one.mp4").toPath()).equals("first"))
            throw new AssertionError("Migrated content changed");
        checkBase(0);
        checkBase(3, "01.jpg", "03.jpg");
        checkBase(100, "0099.jpg", "100.png", "notes.jpg");
        checkBase(2, "photo.jpg", "cover.png");
        checkBase(2, "01.jpg", "01.png");
        try {
            appendBase(Arrays.asList("999999999999999.jpg"));
            throw new AssertionError("Oversized numbering must be rejected");
        } catch (IllegalArgumentException expected) {}
        System.out.println("Concurrent commits/galleries, file migration, append numbering checks passed");
    }
}
""".replace("APPEND_METHOD", append_method).replace("MOVE_METHOD", move_method).replace("COMMIT_METHODS", "\n".join(commit_methods)).replace("GALLERY_METHODS", "\n".join(gallery_methods))

with tempfile.TemporaryDirectory(prefix="native-tools-", dir=root / "build") as temp:
    source = Path(temp) / "NativeToolsCheck.java"
    source.write_text(harness, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", str(source)], check=True)
    import os
    subprocess.run(["java", "-Xmx128m", "-XX:ReservedCodeCacheSize=32m", "-cp", temp, "NativeToolsCheck", temp], check=True)
