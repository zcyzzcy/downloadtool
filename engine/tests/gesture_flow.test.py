"""Compile the production gesture handlers against small deterministic UI doubles."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
base = root / 'engine/app/src/main/java/com/palworld/vd'


def method(text, marker):
    start = text.index(marker)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


activity = (base / 'VideoActivity.java').read_text(encoding='utf-8')
box = (base / 'VideoBox.java').read_text(encoding='utf-8')
browser = (base / 'BrowserLayer.java').read_text(encoding='utf-8')
bars = [method(text[text.index('private class PBar'):], '@Override public boolean onTouchEvent')
        for text in (activity, box)]
swipe = method(browser, 'private android.view.View.OnTouchListener swipeToClose').replace('android.view.View.OnTouchListener', 'TouchListener').replace('private TouchListener', 'TouchListener')
seek = method(box, 'private void handleSeekTouch')
common = r"""
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 final int action; final float x,y,rawX,rawY;
 MotionEvent(int a,float x,float y){this(a,x,y,x,y);}
 MotionEvent(int a,float x,float y,float rx,float ry){action=a;this.x=x;this.y=y;rawX=rx;rawY=ry;}
 float getRawX(){return rawX;} float getRawY(){return rawY;}
 int getActionMasked(){return action;} float getX(){return x;} float getY(){return y;}
}
class Parent { boolean blocked; void requestDisallowInterceptTouchEvent(boolean b){blocked=b;} }
interface TouchListener { boolean onTouch(View v,MotionEvent e); }
class View {
 float translation,alpha=1; int width=2000,clicks; final Parent parent=new Parent();
 int getWidth(){return width;} Parent getParent(){return parent;}
 boolean onTouchEvent(MotionEvent e){return false;} boolean performClick(){clicks++;return true;}
 float getTranslationX(){return translation;} void setTranslationX(float x){translation=x;}
 Animation animate(){return new Animation(this);} void invalidate(){} void cancelLongPress(){} void setPressed(boolean p){}
}
class Animation {
 View view; Runnable end; Animation(View v){view=v;}
 Animation translationX(float x){view.translation=x;return this;} Animation alpha(float a){view.alpha=a;return this;}
 Animation setDuration(int ms){return this;} Animation withEndAction(Runnable r){end=r;return this;}
 void start(){if(end!=null)end.run();}
}
class Player { long duration=1000,position=200; int seeks;
 long getDuration(){return duration;} long getCurrentPosition(){return position;}
 void seekTo(long t){position=t;seeks++;}
}
class Handler {void removeCallbacks(Runnable r){} }
class Label {void setText(String s){} }
class Surface extends View {
 Player player=new Player(); Handler h=new Handler(); Runnable hideHudR=()->{},hudHideR=()->{};
 Label curT=new Label(); boolean scrub; float frac; int swipeMode; static final int SWIPE_NONE=0,SWIPE_SEEK=1,SWIPE_EPISODE=2;
 int playlistCount=1,episodeSteps; void stepEpisode(int d){episodeSteps+=d;}
 float downX,downY; long scrubFromMs,scrubTargetMs;
 long durMs(){return player.duration;} float clamp01(float f){return Math.max(0,Math.min(1,f));}
 void setFrac(float f){frac=f;} void showHud(){} void cueHide(){} void cue(String s){} void scrubCue(String s){}
 String fmt(long t){return String.valueOf(t);} int dp(int v){return v;}
}
"""
classes = '\n'.join('class Bar' + str(i) + ' extends Surface {' + body + '}' for i, body in enumerate(bars))
math = (base / 'PlaybackMath.java').read_text(encoding='utf-8').replace('package com.palworld.vd;', '')
source = math + common + classes + '\nclass Browser extends Surface {' + swipe + '}\nclass SwipePlayer extends Surface {' + seek + r"""
 void event(MotionEvent e){handleSeekTouch(e);}
}
public class GestureRegression {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  for(Surface bar:new Surface[]{new Bar0(),new Bar1()}){
   bar.onTouchEvent(new MotionEvent(0,0,0));bar.onTouchEvent(new MotionEvent(2,1000,0));
   bar.onTouchEvent(new MotionEvent(3,1000,0));
   check(bar.player.seeks==0 && !bar.scrub && Math.abs(bar.frac-.2f)<.001f);
   bar.onTouchEvent(new MotionEvent(0,0,0));bar.onTouchEvent(new MotionEvent(2,1000,0));
   bar.onTouchEvent(new MotionEvent(1,1000,0));check(bar.player.seeks==1 && bar.player.position==500 && bar.clicks==1);
   bar.width=0;bar.onTouchEvent(new MotionEvent(0,0,0));check(Float.isFinite(bar.frac));
  }
  for(Surface bar:new Surface[]{new Bar0(),new Bar1()}){
   bar.player.duration=0;bar.onTouchEvent(new MotionEvent(0,1000,0));bar.player.duration=1000;
   bar.onTouchEvent(new MotionEvent(2,1500,0));bar.onTouchEvent(new MotionEvent(1,1500,0));check(bar.player.seeks==0);
  }
  SwipePlayer player=new SwipePlayer();player.event(new MotionEvent(0,0,0));player.event(new MotionEvent(2,1000,0));
  player.event(new MotionEvent(3,1000,0));check(player.player.seeks==0 && player.swipeMode==0);
  player.event(new MotionEvent(0,0,0));player.event(new MotionEvent(2,1000,0));player.event(new MotionEvent(1,1000,0));
  check(player.player.seeks==1 && player.player.position==700);
  player.playlistCount=3;
  player.event(new MotionEvent(0,100,300));player.event(new MotionEvent(2,100,100));player.event(new MotionEvent(1,100,100));check(player.episodeSteps==1);
  player.event(new MotionEvent(0,100,100));player.event(new MotionEvent(2,100,300));player.event(new MotionEvent(3,100,300));check(player.episodeSteps==1);
  player.event(new MotionEvent(0,100,100));player.event(new MotionEvent(2,100,300));player.event(new MotionEvent(1,100,300));check(player.episodeSteps==0);
  Browser browser=new Browser();View card=new View();card.width=300;int[] closed={0};
  TouchListener touch=browser.swipeToClose(card,()->closed[0]++);
  touch.onTouch(card,new MotionEvent(0,0,0));touch.onTouch(card,new MotionEvent(2,200,0));
  touch.onTouch(card,new MotionEvent(3,200,0));check(closed[0]==0 && card.translation==0 && !card.parent.blocked);
  touch.onTouch(card,new MotionEvent(0,0,0));touch.onTouch(card,new MotionEvent(2,200,0));
  touch.onTouch(card,new MotionEvent(2,40,0,240,0));check(card.translation==240);
  touch.onTouch(card,new MotionEvent(1,0,0,240,0));check(closed[0]==1 && !card.parent.blocked);
 }
}
"""
with tempfile.TemporaryDirectory() as directory:
    java = Path(directory) / 'GestureRegression.java'
    java.write_text(source, encoding='utf-8')
    subprocess.run([shutil.which('javac'), '-encoding', 'UTF-8', str(java)], check=True)
    subprocess.run([shutil.which('java'), '-Xmx128m', '-XX:ReservedCodeCacheSize=32m', '-cp', directory, 'GestureRegression'], check=True)
print('Cancelled gestures, short-video seek, zero-width bars and tab swipe checks passed')
