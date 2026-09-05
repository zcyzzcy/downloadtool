# VD 常驻下载守护进程
# App 通过 stdin 发 JSON 行请求，本进程用 stdout 回 JSON 行事件（progress/done/error）。
# 常驻的意义：yt-dlp 的 Python 启动 + 模块加载在手机上要 1.5~3 秒，以前每个任务都重付一遍；
# 现在进程活一整个会话，所有任务直接进入网络解析阶段。
# 启动：libpython.so vd_daemon.py <yt-dlp zipapp 路径> <ffmpeg 二进制路径>
# 环境变量与 youtubedl-android 完全一致：LD_LIBRARY_PATH / SSL_CERT_FILE / PYTHONHOME / HOME / TMPDIR / PATH
# （另加 VD_SLOTS=并发数、PYTHONIOENCODING=utf-8）

import sys, os, json, gc, threading

YTDLP_ZIP = sys.argv[1] if len(sys.argv) > 1 else ''
FFMPEG_BIN = sys.argv[2] if len(sys.argv) > 2 else ''

_emit_lock = threading.Lock()


def emit(obj):
    with _emit_lock:
        try:
            sys.stdout.write(json.dumps(obj, ensure_ascii=False) + u"\n")
            sys.stdout.flush()
        except Exception:
            pass


def import_ytdlp():
    # 首选：zipapp 直接挂 sys.path（zipimport 支持）
    try:
        sys.path.insert(0, YTDLP_ZIP)
        import yt_dlp
        return yt_dlp
    except Exception:
        pass
    # 兜底：zipimport 不认这个包体时解包到缓存目录（只做一次）
    tmp = os.environ.get('TMPDIR') or '.'
    dst = os.path.join(tmp, 'vd_ytdlp_pkg')
    marker = os.path.join(tmp, 'vd_ytdlp_ok')
    try:
        import shutil, zipfile
        if not os.path.exists(marker):
            if os.path.exists(dst):
                shutil.rmtree(dst)
            with zipfile.ZipFile(YTDLP_ZIP) as z:
                z.extractall(dst)
            with open(marker, 'w'):
                pass
        sys.path.insert(0, dst)
        import yt_dlp
        return yt_dlp
    except Exception as e:
        emit({'type': 'fatal', 'error': 'import yt_dlp failed: %r' % (e,)})
        sys.exit(1)


yt_dlp = import_ytdlp()
YoutubeDL = yt_dlp.YoutubeDL
try:
    from yt_dlp.utils import DownloadCancelled
except Exception:
    class DownloadCancelled(Exception):
        pass

SLOTS = threading.Semaphore(max(1, int(os.environ.get('VD_SLOTS') or 3)))
CANCEL = set()
CANCEL_LOCK = threading.Lock()
RUNNING = set()
RUNNING_LOCK = threading.Lock()


def is_cancelled(rid):
    with CANCEL_LOCK:
        return rid in CANCEL


def mark_cancel(rid):
    with CANCEL_LOCK:
        CANCEL.add(rid)


def clear_cancel(rid):
    with CANCEL_LOCK:
        CANCEL.discard(rid)


def hook_factory(rid):
    def hook(d):
        if is_cancelled(rid):
            raise DownloadCancelled('cancelled')
        st = d.get('status')
        if st == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            done = d.get('downloaded_bytes') or 0
            speed = d.get('speed') or 0
            # 行格式与旧 CLI 输出保持一致（页面按 "of XMiB at YMiB/s" 正则解析速度和大小）
            if total:
                pct = int(done * 100 / total)
                line = '%.1f%% of %.2fMiB at %.2fMiB/s' % (done * 100.0 / total, total / 1048576.0, speed / 1048576.0)
            else:
                pct = 0
                line = 'at %.2fMiB/s' % (speed / 1048576.0)
            obj = {'type': 'progress', 'id': rid, 'progress': min(99, pct), 'line': line}
            t = (d.get('info_dict') or {}).get('title')
            if t:
                obj['title'] = t
            emit(obj)
        elif st == 'finished':
            obj = {'type': 'progress', 'id': rid, 'progress': 99, 'line': 'download finished, merging'}
            info = d.get('info_dict') or {}
            t = info.get('title')
            if t:
                obj['title'] = t
            fn = d.get('filename')
            if fn:
                obj['filename'] = os.path.basename(fn)
            emit(obj)
    return hook


def run_download(req):
    rid = req.get('id') or ''
    url = req.get('url') or ''
    with RUNNING_LOCK:
        RUNNING.add(rid)
    try:
        SLOTS.acquire()
        # 排队期间可能已收到取消：先查再清（原来的先 clear_cancel 会把排队时到的取消抹掉）
        if is_cancelled(rid):
            emit({'type': 'error', 'id': rid, 'line': u'\u4efb\u52a1\u88ab\u53d6\u6d88'})
            return
        clear_cancel(rid)
        params = {
            'noplaylist': True,
            'concurrent_fragment_downloads': 8,
            'retries': 3,
            'extractor_retries': 2,
            'socket_timeout': 15,
            'updatetime': False,
            'no_warnings': True,
            'quiet': True,
            'progress_hooks': [hook_factory(rid)],
            'ffmpeg_location': FFMPEG_BIN,
        }
        if req.get('format'):
            params['format'] = req['format']
        if req.get('outtmpl'):
            params['outtmpl'] = req['outtmpl']
        if req.get('merge'):
            params['merge_output_format'] = 'mp4'
        cf = req.get('cookiefile')
        if cf and os.path.exists(cf):
            params['cookiefile'] = cf
        dl = req.get('downloader')
        if dl:
            params['external_downloader'] = {'default': dl}
            args = ['--summary-interval=1']
            cert = os.environ.get('SSL_CERT_FILE')
            if cert:
                args.append('--ca-certificate=' + cert)
            params['external_downloader_args'] = {'default': args}
        with YoutubeDL(params) as ydl:
            info = ydl.extract_info(url, download=True)
        # 上报真实产物清单：按 requested_downloads 的最终文件名（合并/转码后的），
        # App 侧按清单认领，不再扫目录猜（并发任务会把彼此的文件认错）
        files = []
        try:
            if info:
                for e in (info.get('entries') or [info]):
                    if not e:
                        continue
                    for rd in (e.get('requested_downloads') or []):
                        fp = (rd or {}).get('filepath') or ''
                        if fp:
                            files.append(os.path.basename(fp))
        except Exception:
            files = []
        emit({'type': 'done', 'id': rid, 'files': files})
    except DownloadCancelled:
        emit({'type': 'error', 'id': rid, 'line': u'\u4efb\u52a1\u88ab\u53d6\u6d88'})
    except SystemExit:
        emit({'type': 'error', 'id': rid, 'line': u'\u4efb\u52a1\u88ab\u53d6\u6d88'})
    except BaseException as e:
        try:
            msg = str(e) or repr(e)
        except Exception:
            msg = 'unknown error'
        emit({'type': 'error', 'id': rid, 'line': msg})
    finally:
        with RUNNING_LOCK:
            RUNNING.discard(rid)
        try:
            SLOTS.release()
        except Exception:
            pass
        clear_cancel(rid)
        gc.collect()


def handle(line):
    try:
        req = json.loads(line)
    except Exception:
        emit({'type': 'error', 'id': '-', 'line': 'bad request'})
        return
    act = req.get('action') or 'download'
    rid = req.get('id') or ''
    if act == 'cancel':
        # 只对还在跑的任务记取消：已完结任务的取消是迟到消息，记进去会永远留在 CANCEL 里
        if rid:
            with RUNNING_LOCK:
                alive = rid in RUNNING
            if alive:
                mark_cancel(rid)
        return
    if act == 'ping':
        emit({'type': 'pong', 'id': rid})
        return
    threading.Thread(target=run_download, args=(req,), daemon=True).start()


emit({'type': 'ready'})
for raw in sys.stdin:
    line = raw.strip()
    if not line:
        continue
    handle(line)
emit({'type': 'bye'})
