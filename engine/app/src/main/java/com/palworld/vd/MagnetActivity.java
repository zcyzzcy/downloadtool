package com.palworld.vd;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.List;

/** Video picker; leaving this screen never cancels the existing download task. */
public class MagnetActivity extends Activity {
    private MagnetStream stream;
    private TextView state;
    private LinearLayout list;
    private LinearLayout root;
    private String rendered = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int sp,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setPadding(dp(18),dp(12),dp(18),dp(12)); return v;
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        stream=MagnetStream.get(getIntent().getStringExtra("task"));
        if(stream==null) { Toast.makeText(this,"此任务暂不支持预览，请重试下载或完成后播放",Toast.LENGTH_LONG).show(); finish(); return; }
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFFF3F7FB);
        fit();
        TextView back=text("‹ 返回下载",16,0xFF155E75); back.setOnClickListener(v->finish()); root.addView(back);
        TextView title=text("磁力边下边播",24,0xFF142D45); title.setTypeface(null,Typeface.BOLD); root.addView(title);
        root.addView(text("选择一个视频开始播放。整条磁力继续下载，已下载片段会直接复用。拖到缺片位置时需要等待。",14,0xFF52677C));
        state=text("正在获取文件列表…",14,0xFF0E7490); root.addView(state);
        ScrollView scroll=new ScrollView(this); list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(16),0,dp(16),dp(20)); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private final Runnable update=new Runnable() {
        @Override public void run() {
            if(stream==null || isFinishing()) return;
            state.setText(stream.note());
            List<MagnetStream.Entry> videos=stream.videos();
            StringBuilder key=new StringBuilder(); for(MagnetStream.Entry e:videos) key.append(e.index).append(':').append(e.name).append('\n');
            key.append(stream.failed()).append(stream.hasMetadata());
            if(!rendered.equals(key.toString())) {
                rendered=key.toString(); list.removeAllViews();
                if(videos.isEmpty()) list.addView(text(stream.hasMetadata() ? "此磁力中没有可预览的视频，文件下载仍会继续。" : "文件列表取回后，视频会显示在这里。无做种的资源可能需要较长时间；也可以返回首页稍后再看。",15,0xFF52677C));
                for(MagnetStream.Entry e:videos) {
                    TextView row=text("▶  "+e.name+"\n"+MagnetStream.size(e.length)+"  ·  边下边播",16,0xFF173B55);
                    GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); row.setBackground(bg);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(10); list.addView(row,lp);
                    row.setEnabled(!stream.failed()); row.setAlpha(stream.failed()?0.5f:1f);
                    row.setOnClickListener(v -> {
                        try {
                            String url=stream.url(e.index);
                            startActivity(new Intent(MagnetActivity.this,VideoActivity.class)
                                .putExtra("files",new String[]{url}).putExtra("title",e.name)
                                .putExtra("torrentSession",getIntent().getStringExtra("task")));
                        } catch(Exception error) { Toast.makeText(MagnetActivity.this,"视频暂不可用，请稍后重试",Toast.LENGTH_LONG).show(); }
                    });
                }
            }
            h.postDelayed(this,1000);
        }
    };
    private void fit() { int pad=Math.max(0,(getResources().getDisplayMetrics().widthPixels-dp(700))/2); root.setPadding(pad,0,pad,0); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); fit(); }
    @Override protected void onResume() { super.onResume(); h.removeCallbacks(update); h.post(update); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(update); }
    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy(); }
}
