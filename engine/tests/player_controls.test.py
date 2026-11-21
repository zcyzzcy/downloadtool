"""Exercise production speed/intent/snapshot controls and shared seek math on JVM."""
from pathlib import Path
import subprocess,tempfile,shutil
base=Path('engine/app/src/main/java/com/palworld/vd')
s=(base/'VideoBox.java').read_text(encoding='utf-8')
def block(marker):
 a=s.index(marker);i=s.index('{',a);depth=1;b=i+1
 while depth:
  depth+=(s[b]=='{')-(s[b]=='}');b+=1
 return s[a:b]
methods='\n'.join(block(x) for x in ['private void restoreHeldSpeed(', 'private void setSpeedSel(', 'private void togglePlayback(', 'private void saveSnap('])
math=(base/'PlaybackMath.java').read_text(encoding='utf-8').replace('package com.palworld.vd;','')
java=math+r"""
class View {static int GONE=8;}
class Player {
 static final int STATE_BUFFERING=2,STATE_READY=3,STATE_ENDED=4;
 float rate=1;int writes;boolean requested=true;int state=STATE_READY;
 void setPlaybackSpeed(float f){rate=f;writes++;}
 boolean getPlayWhenReady(){return requested;}boolean isPlaying(){return requested&&state==STATE_READY;}
 int getPlaybackState(){return state;}long getCurrentPosition(){return 12345;}long getDuration(){return 98765;}
}
class Widget {String text;int visibility;void setVisibility(int n){visibility=n;}void setText(String s){text=s;}}
class Prefs {String saved;int writes;Prefs edit(){return this;}Prefs putString(String k,String v){saved=v;writes++;return this;}void apply(){}}
class Host {final Prefs prefs=new Prefs();Prefs getSharedPreferences(String n,int m){return prefs;}}
public class PlayerControlsCheck {
 Player player=new Player();Host act=new Host();float speed=1.5f;boolean holdingSpeed,errored;
 Widget spdBtn=new Widget(),speedMenu=new Widget();String snapshot;int hides,pauses,plays;
 void cueHide(){hides++;}void cue(String s){}void showHud(){}String trim(float f){return Float.toString(f);}
 void snap(String s){snapshot=s;}void pause(){pauses++;player.requested=false;}void play(){plays++;player.requested=true;}
"""+methods+r"""
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  for(String invalid:new String[]{null,"","NaN","Infinity","-Infinity","0","-1","10"})check(PlaybackMath.speed(invalid)==1,"invalid saved speed: "+invalid);
  check(PlaybackMath.speed("3")==3&&PlaybackMath.speed("0.75")==.75f,"valid speed preserved");
  check(PlaybackMath.swipe(3000000,7200000,1000,2000)==3060000,"long-film swipe is capped at two minutes per screen");
  check(PlaybackMath.swipe(200,1000,1000,2000)==700,"short video retains fine seek");
  check(PlaybackMath.swipe(1000,10000,-99999,200)==0,"lower clamp");
  check(PlaybackMath.swipe(5000,10000,99999,200)==9750,"upper clamp stays before end");
  check(PlaybackMath.swipe(123,0,999,200)==123&&PlaybackMath.swipe(123,1000,10,0)==123,"unknown metadata/zero width never jump");
  PlayerControlsCheck c=new PlayerControlsCheck();c.holdingSpeed=true;c.player.rate=2;c.restoreHeldSpeed();
  check(c.player.rate==1.5f&&!c.holdingSpeed&&c.act.prefs.writes==0,"temporary hold restores chosen speed without persisting 2x");
  int writes=c.player.writes;c.restoreHeldSpeed();check(c.player.writes==writes,"restore is idempotent");
  c.holdingSpeed=true;c.setSpeedSel(3);check(c.speed==3&&c.player.rate==3&&c.act.prefs.saved.equals("3.0"),"explicit speed persists across videos");
  c.setSpeedSel(Float.NaN);check(c.speed==1&&c.player.rate==1,"bridge speed validation");
  c.player.state=Player.STATE_BUFFERING;c.player.requested=true;c.togglePlayback();
  check(c.pauses==1&&!c.player.requested,"pause during buffering follows playback intent rather than isPlaying");
  c.saveSnap();check(c.snapshot.startsWith("paused,"),"paused buffering is not reported as actively loading");
  c.togglePlayback();check(c.plays==1&&c.player.requested,"resume after paused buffer");c.saveSnap();check(c.snapshot.startsWith("loading,"),"active buffering remains distinguishable");
  c.errored=true;c.snapshot="error";c.saveSnap();check(c.snapshot.equals("error"),"error state remains frozen");
  c.player=null;c.holdingSpeed=true;c.restoreHeldSpeed();check(!c.holdingSpeed,"release after player disposal is safe");
  System.out.println("Player speed persistence, hold restoration, pause during buffering, loading snapshots, invalid preferences and bounded long/short seeks passed");
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'PlayerControlsCheck.java';p.write_text(java,encoding='utf-8')
 javac=shutil.which('javac') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\javac.exe'
 java_exe=shutil.which('java') or r'D:\software\Microsoft\jdk-17.0.20.8-hotspot\bin\java.exe'
 subprocess.run([javac,'-J-Xmx128m','-encoding','UTF-8',str(p)],check=True)
 subprocess.run([java_exe,'-Xmx128m','-cp',d,'PlayerControlsCheck'],check=True)
