"""Use production Java routing and the bundled yt-dlp selector on sparse manifests."""
from pathlib import Path
import subprocess, tempfile, shutil, sys

root=Path(__file__).resolve().parents[2]
source=(root/'engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
def method(name):
    i=source.index('    private static '+name)
    return source[i:source.index('\n    }',i)+6]
methods='\n'.join(method(n) for n in ['String formatFilter(', 'boolean isDirectMediaUrl(', 'boolean isDirectStreamUrl(', 'String downloadFormat('])
java='class FormatCheck {'+methods+'''public static void main(String[] args){for(String url:args)System.out.println(downloadFormat(url,"auto"));}}'''
urls=['https://cdn.test/one.m3u8?sig=keep','https://cdn.test/two.mpd','https://www.bilibili.com/video/BV123',
      'https://cdn.test/direct.mp4','file:///one.m3u8','https://example.test/page?src=one.m3u8']
with tempfile.TemporaryDirectory() as directory:
    p=Path(directory)/'FormatCheck.java';p.write_text(java,encoding='utf-8')
    subprocess.run([shutil.which('javac'),'-J-Xmx128m','-encoding','UTF-8',str(p)],check=True)
    selectors=subprocess.check_output([shutil.which('java'),'-Xmx128m','-cp',directory,'FormatCheck',*urls],text=True).splitlines()
assert selectors[0]==selectors[1] and selectors[0]!=selectors[2]
assert selectors[3]=='best'
assert selectors[4]==selectors[5]==selectors[2]
sys.path.insert(0,str(root/'engine/app/src/main/assets/ytdlp_hot.zipapp'))
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError, ExtractorError
def select(selector,formats):
    with YoutubeDL({'format':selector,'quiet':True,'no_warnings':True}) as ydl:
        return ydl.process_ie_result({'id':'fixture','title':'Fixture','extractor':'fixture','extractor_key':'Fixture','webpage_url':'https://fixture.test/post','formats':formats},download=False)
# A media playlist has neither CODECS nor RESOLUTION. It is still playable.
sparse={'format_id':'hls','url':urls[0],'ext':'mp4','protocol':'m3u8_native'}
assert select(selectors[0],[sparse])['url']==urls[0]
dash=[{'format_id':'v','url':'https://cdn.test/v.mp4','ext':'mp4','vcodec':'avc1','acodec':'none','height':720},
      {'format_id':'a','url':'https://cdn.test/a.m4a','ext':'m4a','vcodec':'none','acodec':'aac'}]
assert len(select(selectors[1],dash)['requested_formats'])==2
try:
    select(selectors[2],[{'format_id':'photo','url':'https://cdn.test/cover.jpg','ext':'jpg','vcodec':'none','acodec':'none'}])
except (DownloadError, ExtractorError):
    pass
else:
    raise AssertionError('Page selector accepted a cover as video')
print('Sparse HLS, split DASH, signed manifest routing and strict page-cover rejection passed')
