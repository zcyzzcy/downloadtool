# VD 常驻下载守护进程
# App 通过 stdin 发 JSON 行请求，本进程用 stdout 回 JSON 行事件（progress/done/error）。
# 常驻的意义：yt-dlp 的 Python 启动 + 模块加载在手机上要 1.5~3 秒，以前每个任务都重付一遍；
# 现在进程活一整个会话，所有任务直接进入网络解析阶段。
# 启动：libpython.so vd_daemon.py <yt-dlp zipapp 路径> <ffmpeg 二进制路径>
# 环境变量与 youtubedl-android 完全一致：LD_LIBRARY_PATH / SSL_CERT_FILE / PYTHONHOME / HOME / TMPDIR / PATH
# （另加 VD_SLOTS=并发数、PYTHONIOENCODING=utf-8）
# v1.93：字幕/AI 识别/字幕导出整体移除，本进程只负责下载。

import sys, os, time, json, threading, re, subprocess
import http.cookiejar
import urllib.request
from urllib.parse import urlsplit, unquote
from bilibili_collection import resolve_collection

# 编码自愈：全靠启动方设 PYTHONIOENCODING=utf-8 不保险——漏设时 Python 退回 ANSI/ASCII，
# 每条中文事件（报错文案/标题）都 UnicodeEncodeError 且被 emit 静默吞掉，App 侧表现成
# "任务无声卡死"。这里直接把流钉死成 UTF-8（Java 侧读取也是 UTF-8）
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    sys.stdin.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

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
from zhuliwa import ZhuliwaIE
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
TASK_PROCESSES = {}
PROCESS_LOCK = threading.Lock()


# External downloaders have no progress hook while waiting or retrying. Track
# each child by its owning task so cancellation can stop only that task.
from yt_dlp.downloader import external as external_downloader
BasePopen = external_downloader.Popen


class TaskPopen(BasePopen):
    def __init__(self, *args, **kwargs):
        self.task_id = getattr(threading.current_thread(), 'vd_task_id', None)
        if self.task_id and is_cancelled(self.task_id):
            raise DownloadCancelled('cancelled')
        super().__init__(*args, **kwargs)
        if self.task_id:
            with PROCESS_LOCK:
                TASK_PROCESSES.setdefault(self.task_id, set()).add(self)
            if is_cancelled(self.task_id):
                self.kill()

    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            if self.task_id:
                with PROCESS_LOCK:
                    processes = TASK_PROCESSES.get(self.task_id)
                    if processes is not None:
                        processes.discard(self)
                        if not processes:
                            TASK_PROCESSES.pop(self.task_id, None)


external_downloader.Popen = TaskPopen


def aria2_call_process(self, cmd, info_dict):
    # Capture summaries here so external-download progress stays inside the
    # JSON protocol and uses the same cancellation-aware hook as native HTTP.
    task_id = self.params['_vd_task_id']
    threading.current_thread().vd_task_id = task_id
    progress_hook = hook_factory(task_id, self.params.get('_vd_title') or '')
    # A download returned 255 during emulator testing. Its underlying cause
    # remains unknown, so retain bounded recovery and report safe error codes.
    # Reuse the same partial file and control file for bounded recovery.
    for attempt in range(3):
        recent = []
        error_codes = set()
        previous = None
        done = total = 0
        with TaskPopen(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            for line in process.stdout:
                recent.append(line)
                recent = recent[-20:]
                error_codes.update(re.findall(r'\b(?:errorCode|errno)=\d+', line))
                match = re.search(r'\s(\d+)B?/(\d+)B?\((\d+)%\).*?DL:(\d+)B?', line)
                if match:
                    done, total, _, speed = map(int, match.groups())
                    now = time.monotonic()
                    if previous is not None:
                        speed = max(0, done - previous[0]) / max(0.001, now - previous[1])
                    previous = (done, now)
                    progress_hook({'status': 'downloading', 'downloaded_bytes': done,
                                   'total_bytes': total, 'speed': speed, 'info_dict': info_dict})
            return_code = process.wait()
        if is_cancelled(task_id):
            raise DownloadCancelled('cancelled')
        if return_code == 255 and attempt == 2:
            detail = ', '.join(sorted(error_codes)) or '未返回具体错误编号'
            raise RuntimeError(f'下载器异常退出（255），已尝试 3 次。{detail}。可点重试继续')
        if return_code != 255:
            return None, ''.join(recent), return_code
        progress_hook({'status': 'downloading', 'downloaded_bytes': done,
                       'total_bytes': total, 'speed': 0, 'info_dict': info_dict,
                       'note': f'下载器异常退出（255），正在进行第 {attempt + 2}/3 次尝试'})


external_downloader.Aria2cFD._call_process = aria2_call_process


def is_cancelled(rid):
    with CANCEL_LOCK:
        return rid in CANCEL


def mark_cancel(rid):
    with CANCEL_LOCK:
        CANCEL.add(rid)
    with PROCESS_LOCK:
        processes = list(TASK_PROCESSES.get(rid, ()))
    for process in processes:
        try:
            process.kill()
        except OSError:
            pass


def clear_cancel(rid):
    with CANCEL_LOCK:
        CANCEL.discard(rid)


class TaskYoutubeDL(YoutubeDL):
    def urlopen(self, request):
        rid = getattr(threading.current_thread(), 'vd_task_id', None)
        if rid and is_cancelled(rid):
            raise DownloadCancelled('cancelled')
        try:
            response = super().urlopen(request)
        except BaseException:
            if rid and is_cancelled(rid):
                raise DownloadCancelled('cancelled') from None
            raise
        if rid and is_cancelled(rid):
            response.close()
            raise DownloadCancelled('cancelled')
        return response


def hook_factory(rid, title_hint):
    # v1.99：进度节流——yt-dlp 按 chunk 高频回调（16 并发分片时每秒几十上百条），
    # 全量转发只白烧管道/JSON 解析/Handler post（Java 侧 200ms 节流在最后一跳，上游全白干）。
    # 距上次上报 <250ms 且百分比没走满 1 就跳过；finished 永远放行
    last = [0.0, -1]

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
            now = time.monotonic()
            if not d.get('note') and now - last[0] < 0.25 and abs(pct - last[1]) < 1:
                return
            last[0], last[1] = now, pct
            obj = {'type': 'progress', 'id': rid, 'progress': min(99, pct), 'line': line}
            obj.update(downloaded_bytes=done, total_bytes=total, speed=speed,
                       total_estimated=not bool(d.get('total_bytes')), status='downloading')
            if d.get('note'):
                obj['note'] = d['note']
            # v1.97：App 传了强制命名（页面标题）就用它——通用提取器对直链给的"标题"是 URL 尾巴垃圾
            t = title_hint or (d.get('info_dict') or {}).get('title')
            if t:
                obj['title'] = t
            emit(obj)
        elif st == 'finished':
            obj = {'type': 'progress', 'id': rid, 'progress': 99, 'line': 'download finished, merging'}
            obj.update(downloaded_bytes=d.get('downloaded_bytes') or 0,
                       total_bytes=d.get('total_bytes') or 0, speed=0, status='finished')
            info = d.get('info_dict') or {}
            t = title_hint or info.get('title')
            if t:
                obj['title'] = t
            fn = d.get('filename')
            if fn:
                obj['filename'] = os.path.basename(fn)
            emit(obj)
    return hook


def direct_media_info(req):
    if not req.get('direct_media'):
        return None
    parsed = urlsplit(req.get('url') or '')
    extension = parsed.path.rsplit('.', 1)[-1].lower()
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or extension not in (
            'mp4', 'm4v', 'mkv', 'webm', 'mov', 'flv', 'avi', 'mp3', 'm4a', 'aac', 'flac', 'wav', 'ogg', 'opus'):
        return None
    name = unquote(parsed.path.rsplit('/', 1)[-1]).rsplit('.', 1)[0]
    return {'id': req.get('id') or 'media', 'title': req.get('title') or name or '媒体',
            'url': req['url'], 'ext': extension, 'webpage_url': (req.get('headers') or {}).get('Referer') or req['url']}


def run_download(req):
    rid = req.get('id') or ''
    url = req.get('url') or ''
    with RUNNING_LOCK:
        RUNNING.add(rid)
    threading.current_thread().vd_task_id = rid
    try:
        SLOTS.acquire()
        # 排队期间可能已收到取消：先查再清（原来的先 clear_cancel 会把排队时到的取消抹掉）
        if is_cancelled(rid):
            emit({'type': 'error', 'id': rid, 'line': u'\u4efb\u52a1\u88ab\u53d6\u6d88'})
            return
        def check_cancelled():
            if is_cancelled(rid):
                raise DownloadCancelled('cancelled')

        if req.get('resolve_collection'):
            jar = http.cookiejar.MozillaCookieJar(req.get('cookiefile'))
            if req.get('cookiefile') and os.path.isfile(req['cookiefile']):
                jar.load(ignore_discard=True, ignore_expires=True)
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
            headers = req.get('headers') or {}

            def fetch_json(api_url):
                check_cancelled()
                request = urllib.request.Request(api_url, headers={
                    'User-Agent': headers.get('User-Agent') or 'Mozilla/5.0',
                    'Referer': 'https://www.bilibili.com/',
                })
                with opener.open(request, timeout=15) as response:
                    return json.load(response)

            collection = resolve_collection(url, fetch_json, check_cancelled)
            if collection:
                check_cancelled()
                emit({'type': 'collection', 'id': rid, **collection})
                return
        params = {
            '_vd_task_id': rid,
            '_vd_title': req.get('title') or '',
            'noplaylist': True,
            # v1.98：HLS 分片并发 8→16（aria2 不可用/加密流时 yt-dlp 自己下分片，手机上并发太保守）
            'concurrent_fragment_downloads': 16,
            # v1.75：切网/断流不再轻易判死——以前 3 次重试×15s 超时，换个网络（Wi-Fi↔流量
            # 切换通常要几十秒）任务直接报错，重试又从头下。现在 20 次×20s+5s 间隔，
            # 扛得住几分钟的网络空窗；配合 Java 侧保留断点目录，重试接着上次的进度下
            'retries': 20,
            'fragment_retries': 20,
            'extractor_retries': 3,
            'socket_timeout': 15,
            'retry_sleep_functions': {'http': 5, 'fragment': 5},
            # v1.98：部分站点证书链在手机上校验慢/失败重握手，跳过校验提速（下载场景可接受）
            'nocheckcertificate': True,
            'updatetime': False,
            'no_warnings': True,
            'quiet': True,
            'noprogress': True,  # Only JSON hooks may write to the daemon protocol.
            'progress_hooks': [hook_factory(rid, req.get('title') or '')],
            'ffmpeg_location': FFMPEG_BIN,
        }
        headers = req.get('headers') or {}
        if isinstance(headers, dict):
            params['http_headers'] = {k: v for k, v in headers.items()
                                      if k in ('Referer', 'User-Agent') and isinstance(v, str)
                                      and '\r' not in v and '\n' not in v}
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
            # Capture the once-per-second aria2 summaries and forward structured progress.
            args = ['--summary-interval=1', '--show-console-readout=false',
                    '--human-readable=false', '--enable-color=false',
                    '--max-tries=0', '--retry-wait=3', '--continue=true', '--timeout=20']   # v1.75：aria2 无限重试+断点续传（.aria2 控制文件在同目录，重试复用目录即续传）
            cert = os.environ.get('SSL_CERT_FILE')
            if cert:
                args.append('--ca-certificate=' + cert)
            params['external_downloader_args'] = {'default': args}
        is_zhuliwa = ZhuliwaIE.suitable(url)
        if is_zhuliwa:
            # API media may omit codec metadata. Keep every delivered item
            # separately named even when the browser supplied a fixed title.
            params['format'] = 'best'
            if req.get('outtmpl'):
                params['outtmpl'] = os.path.join(
                    os.path.dirname(req['outtmpl']), '%(title).180s-%(id)s.%(ext)s')
        with TaskYoutubeDL(params) as ydl:
            if is_zhuliwa:
                ydl.add_info_extractor(ZhuliwaIE())
                info = ydl.extract_info(url, download=True, ie_key=ZhuliwaIE.ie_key())
            else:
                direct = direct_media_info(req)
                if direct:
                    info = ydl.process_ie_result(direct, download=True)
                    # Expired media links may return a successful HTTP response containing a login page.
                    for item in (info or {}).get('requested_downloads') or []:
                        filename = item.get('filepath') or ''
                        if os.path.isfile(filename):
                            with open(filename, 'rb') as media:
                                prefix = media.read(512).lstrip(b'\xef\xbb\xbf \r\n\t').lower()
                            if prefix.startswith((b'<', b'{', b'[')):
                                os.remove(filename)
                                raise ValueError('媒体链接返回了网页或错误信息，请在网页中重新登录并识别资源')
                else:
                    info = ydl.extract_info(url, download=True)
        check_cancelled()
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
        threading.current_thread().__dict__.pop('vd_task_id', None)
        # v1.99：删掉每任务 gc.collect()——CPython 引用计数已兜底，全量 GC 在手机上
        # 一次 100-500ms，只会拖慢下一个任务启动


def handle(line):
    try:
        req = json.loads(line)
    except Exception:
        emit({'type': 'error', 'id': '-', 'line': 'bad request'})
        return
    # 非 dict JSON（如 "[1]"/"123"）能被 loads 解析但 .get 会抛 AttributeError，
    # 直接击穿主循环让守护进程死亡——所有在飞任务失控。一行防御挡住
    if not isinstance(req, dict):
        emit({'type': 'error', 'id': '-', 'line': 'bad request'})
        return
    act = req.get('action') or 'download'
    rid = req.get('id') or ''
    if act == 'cancel':
        # 只对还在跑的下载记取消（已完结任务的取消是迟到消息，记进去会永远留在 CANCEL 里）
        if rid:
            with RUNNING_LOCK:
                alive = rid in RUNNING
            if alive:
                mark_cancel(rid)
        return
    if act == 'ping':
        emit({'type': 'pong', 'id': rid})
        return
    # Register before scheduling so an immediate cancel cannot miss the task.
    with RUNNING_LOCK:
        RUNNING.add(rid)
    try:
        threading.Thread(target=run_download, args=(req,), daemon=True).start()
    except Exception:
        with RUNNING_LOCK:
            RUNNING.discard(rid)
        raise


# SIGTERM/SIGINT（Java 侧 destroy / 系统 am kill 走得到的话）转成 SystemExit，
# 落到主循环的 finally 里收尾——不被信号直接打死
def _sig_term(signum, frame):
    raise SystemExit(0)


try:
    import signal
    signal.signal(signal.SIGTERM, _sig_term)
    signal.signal(signal.SIGINT, _sig_term)
except Exception:
    pass

emit({'type': 'ready'})
try:
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            handle(line)
        except Exception as e:
            # 单条消息处理炸了不能带走整个守护进程（以前一行意外输入 = 全部在飞任务失控）
            try:
                emit({'type': 'error', 'id': '-', 'line': 'daemon error: %s' % (e,)})
            except Exception:
                pass
    emit({'type': 'bye'})
finally:
    pass
