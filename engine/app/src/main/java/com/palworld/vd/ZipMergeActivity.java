package com.palworld.vd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.4~v1.12 图片合并（桌面版「图片合并工具」的压缩包整理功能移植）：
 * 挑一个或多个 zip → 压缩包按文件时间先后处理 → 每个包里的图片按名称自然排序提取 →
 * 连续重命名为 01/02/03…。去处：新建文件夹（下载/vd/图片合并/<名字>/，重跑先清空）
 * 或续接已有文件夹（编号接在现有最大数字编号后）。
 * v1.6 选文件夹内置列表一键选中（自家目录走 MediaStore 直写免授权，系统选择器兜底）；
 * v1.7 续接写入提速（现有文件一次性预载索引，去掉 SAF 逐张 findFile 的 O(N²)）；
 * v1.8 合并中锁定入口防并发；v1.12 表现形式重做（步骤式布局/单文件移除/保存到单对话框/结果卡）。
 * 支持 jpg/jpeg/png/gif/bmp/webp/tif/tiff/jfif/avif；zip 内中文名按 UTF-8 读、乱码回退 GBK。
 */
public class ZipMergeActivity extends Activity {

    private static final int PICK_ZIP = 21;
    private static final int PICK_DIR = 22;
    private static final String[] IMAGE_EXTS =
        { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tif", ".tiff", ".jfif", ".avif" };
    private static final Pattern DIGIT_SPLIT = Pattern.compile("(\\d+)");
    private static final String OWN_ROOT = "Download/vd/图片合并/";

    private final List<ZipPick> picks = new ArrayList<>();   // 选中的压缩包，按文件时间排序
    // 续接目标：ownRel 非空 = 本工具图片合并目录下的子文件夹（MediaStore 直写，免授权）；
    // treeUri 非空 = 系统选择器挑的外部文件夹（SAF DocumentFile 写入）
    private String ownRel = null;
    private Uri treeUri = null;
    private String destName = "";
    private String destNewName = "";   // 新建模式的名字（对话框输入框实时同步）
    private int basePreview = -1;      // 续接目标现有图片数（-1 = 还没统计出来）
    private int previewGen = 0;
    private boolean appendMode = false;
    private ProgressBar prog;
    private boolean running = false;

    // ---- UI（v1.12 步骤式） ----
    private TextView heroTx, heroSub, step1Val, step2Val, ctaBtn, statusTx, resetBtn;
    private LinearLayout fileList;

    private static class ZipPick {
        Uri uri; String name; long modified; long size;
        ZipPlan plan;            // 选中后后台预读的清单（开始时直接复用）
        boolean planFailed;
    }

    /** 规划结果：排序后的图片名 + 实际解码用的字符集（提取时必须用同一个，否则名字对不上） */
    private static class ZipPlan {
        List<String> names;
        Charset charset;
    }

    private static class FolderInfo {
        String name; int count; long mod;
    }

    /** 提取目标。tree 非空 = 系统选择器挑的外部文件夹（SAF）；否则走 MediaStore 的 sub 子目录。
     *  现有文件名一次性预载成索引：写入时内存判断同名，不再逐张查目录/查库 */
    private static class Dest {
        DocumentFile tree;
        final Map<String, DocumentFile> treeFiles = new HashMap<>();
        final java.util.HashSet<String> msNames = new java.util.HashSet<>();
        String sub = "";
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        destNewName = "合并_" + new SimpleDateFormat("MMdd", Locale.CHINA).format(new Date());
        setContentView(buildUi());
        refreshAll();
    }

    // ================= UI 结构 =================

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(0xFFF2F4F8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sc.addView(root);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFFFFFFFF);
        top.setElevation(dp(4));
        top.setPadding(dp(4), dp(6), dp(10), dp(6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        back.setTextColor(0xFF3358D4);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("图片合并");
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C2B4A);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // 概要卡（状态驱动的大字 + 副标）
        LinearLayout hero = card();
        heroTx = new TextView(this);
        heroTx.setTextSize(17);
        heroTx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heroTx.setTextColor(0xFF1C2B4A);
        hero.addView(heroTx, new LinearLayout.LayoutParams(-2, -2));
        heroSub = new TextView(this);
        heroSub.setTextSize(11.5f);
        heroSub.setTextColor(0xFF8A97B4);
        heroSub.setLineSpacing(0, 1.5f);
        heroSub.setPadding(0, dp(6), 0, 0);
        hero.addView(heroSub, new LinearLayout.LayoutParams(-2, -2));
        root.addView(hero, cardLp());

        // ① 压缩包（整卡可点；选中后卡内列出文件，可单个移除）
        LinearLayout c1 = card();
        c1.setOnClickListener(v -> pickZips());
        c1.addView(stepRow(1, "压缩包", "选 zip 文件", step1Val = valTx()));
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setVisibility(View.GONE);
        c1.addView(fileList);
        root.addView(c1, cardLp());

        // ② 保存到（整卡可点 → 单对话框完成模式 + 名字 + 选文件夹）
        LinearLayout c2 = card();
        c2.setOnClickListener(v -> showDestDialog());
        c2.addView(stepRow(2, "保存到", "新建或续接", step2Val = valTx()));
        root.addView(c2, cardLp());

        // CTA + 进度 + 状态
        ctaBtn = pillBtn("开始合并", false);
        ctaBtn.setOnClickListener(v -> startMerge());
        root.addView(ctaBtn, new LinearLayout.LayoutParams(-1, -2) {{
            setMargins(dp(14), dp(18), dp(14), 0);
        }});
        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(dp(20), dp(14), dp(20), 0);
        root.addView(prog, plp);
        statusTx = new TextView(this);
        statusTx.setTextSize(12);
        statusTx.setTextColor(0xFF5A6B8C);
        statusTx.setLineSpacing(0, 1.5f);
        statusTx.setPadding(dp(18), dp(10), dp(18), 0);
        statusTx.setGravity(Gravity.CENTER);
        statusTx.setVisibility(View.GONE);
        root.addView(statusTx, new LinearLayout.LayoutParams(-1, -2));
        resetBtn = new TextView(this);
        resetBtn.setText("再合并一批");
        resetBtn.setTextSize(13);
        resetBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        resetBtn.setTextColor(0xFF3358D4);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setPadding(0, dp(12), 0, dp(4));
        resetBtn.setVisibility(View.GONE);
        resetBtn.setOnClickListener(v -> {
            picks.clear();
            previewGen++;
            refreshAll();
        });
        root.addView(resetBtn, new LinearLayout.LayoutParams(-1, -2));

        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        return sc;
    }

    /** 步骤行：编号圆 + 标题 + 右侧状态值 + ›（状态值由调用方持有刷新） */
    private LinearLayout stepRow(int no, String title, String hint, TextView val) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(this);
        badge.setText(String.valueOf(no));
        badge.setTextSize(12);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF4F7CFF);
        bg.setShape(GradientDrawable.OVAL);
        badge.setBackground(bg);
        row.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout txts = new LinearLayout(this);
        txts.setOrientation(LinearLayout.VERTICAL);
        txts.setPadding(dp(12), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(14.5f);
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setTextColor(0xFF1C2B4A);
        txts.addView(t1, new LinearLayout.LayoutParams(-2, -2));
        TextView t2 = new TextView(this);
        t2.setText(hint);
        t2.setTextSize(10.5f);
        t2.setTextColor(0xFF8A97B4);
        t2.setPadding(0, dp(2), 0, 0);
        txts.addView(t2, new LinearLayout.LayoutParams(-2, -2));
        row.addView(txts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(val, new LinearLayout.LayoutParams(-2, -2));
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(0xFFB6C2DB);
        chev.setPadding(dp(8), 0, 0, 0);
        row.addView(chev, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView valTx() {
        TextView v = new TextView(this);
        v.setTextSize(12.5f);
        v.setTextColor(0xFF8A97B4);
        return v;
    }

    /** 全量刷新界面（任何状态变化后调用） */
    private void refreshAll() {
        // 压缩包统计
        int total = 0, readyCnt = 0, failedCnt = 0;
        for (ZipPick p : picks) {
            if (p.plan != null) { total += p.plan.names.size(); readyCnt++; }
            else if (p.planFailed) failedCnt++;
        }
        boolean pending = readyCnt + failedCnt < picks.size();

        // 概要
        if (picks.isEmpty()) {
            heroTx.setText("把压缩包里的图片，按顺序整理进一个文件夹");
            heroSub.setText(R.string.zip_sort_help);
        } else if (pending) {
            heroTx.setText(getString(R.string.zip_reading_count, picks.size()));
            heroSub.setText("读取完会显示图片总数和编号范围。");
        } else if (failedCnt > 0 && total == 0) {
            heroTx.setText(getString(R.string.zip_failed_count, failedCnt));
            heroSub.setText("点左下 × 移除读不出的包再试；其他包不受影响。");
        } else {
            heroTx.setText(failedCnt > 0 ? getString(R.string.zip_images_with_failures, total, failedCnt) : getString(R.string.zip_images_total, total));
            heroSub.setText(appendRangeText(total));
        }

        // ① 压缩包
        if (picks.isEmpty()) {
            step1Val.setText("未选");
            step1Val.setTextColor(0xFF8A97B4);
        } else {
            step1Val.setText(getString(R.string.zip_package_images, picks.size(), total));
            step1Val.setTextColor(0xFF3358D4);
        }
        rebuildFileList();

        // ② 保存到
        if (appendMode) {
            if (ownRel != null) {
                step2Val.setText(getString(R.string.zip_append_count, ownRel, Math.max(0, basePreview)));
                step2Val.setTextColor(0xFF3358D4);
            } else if (treeUri != null) {
                step2Val.setText(getString(R.string.zip_append_folder, destName.isEmpty() ? getString(R.string.selected_folder) : destName));
                step2Val.setTextColor(0xFF3358D4);
            } else {
                step2Val.setText("选一个已有文件夹");
                step2Val.setTextColor(0xFF8A97B4);
            }
        } else {
            step2Val.setText(getString(R.string.zip_new_folder, destNewName));
            step2Val.setTextColor(0xFF3358D4);
        }

        // CTA 可用态
        GradientDrawable g = new GradientDrawable();
        boolean canGo = !picks.isEmpty();
        g.setColor(canGo ? 0xFF4F7CFF : 0xFFC3CCE0);
        g.setCornerRadius(dp(24));
        ctaBtn.setBackground(g);
        ctaBtn.setTextColor(0xFFFFFFFF);
    }

    private String appendRangeText(int total) {
        if (total <= 0) return "没有读到图片——确认包里是 jpg/png 等图片文件。";
        if (!appendMode) return "编号 01～" + total + "，存到 下载/vd/图片合并/ 下的新文件夹。";
        if (basePreview >= 0) return "编号接在目标文件夹现有最大数字编号之后。";
        return "编号接着所选文件夹里现有的图片继续。";
    }

    /** ① 卡内的已选文件列表：名称 + 大小/时间 + 单个移除 × */
    private void rebuildFileList() {
        fileList.removeAllViews();
        fileList.setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < picks.size(); i++) {
            final ZipPick p = picks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(0xFFF6F8FC);
            rg.setCornerRadius(dp(9));
            row.setBackground(rg);
            row.setPadding(dp(10), dp(8), dp(6), dp(8));
            LinearLayout txts = new LinearLayout(this);
            txts.setOrientation(LinearLayout.VERTICAL);
            TextView n1 = new TextView(this);
            n1.setText(p.planFailed ? getString(R.string.zip_entry_failed, p.name)
                : p.plan != null ? getString(R.string.zip_entry_ready, p.name, p.plan.names.size())
                : getString(R.string.zip_entry_reading, p.name));
            n1.setTextSize(12.5f);
            n1.setTextColor(p.planFailed ? 0xFFC0392B : 0xFF1C2B4A);
            n1.setSingleLine(true);
            n1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            txts.addView(n1, new LinearLayout.LayoutParams(-2, -2));
            TextView n2 = new TextView(this);
            n2.setText(p.modified > 0 ? getString(R.string.file_size_date, fmtSize(p.size), df.format(new Date(p.modified))) : fmtSize(p.size));
            n2.setTextSize(10.5f);
            n2.setTextColor(0xFF8A97B4);
            n2.setPadding(0, dp(2), 0, 0);
            txts.addView(n2, new LinearLayout.LayoutParams(-2, -2));
            row.addView(txts, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView del = new TextView(this);
            del.setText("×");
            del.setTextSize(16);
            del.setTextColor(0xFF9AA7BF);
            del.setPadding(dp(12), dp(4), dp(12), dp(4));
            del.setOnClickListener(v -> {
                if (running) { Toast.makeText(this, "正在合并，等它跑完", Toast.LENGTH_SHORT).show(); return; }
                picks.remove(p);
                previewGen++;
                refreshAll();
            });
            row.addView(del, new LinearLayout.LayoutParams(-2, -2));
            fileList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private String fmtSize(long b) {
        if (b <= 0) return "";
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(Locale.CHINA, "%.1f MB", b / 1024f / 1024f);
    }

    /** ②「保存到」对话框：模式二选一 + 新建名输入 + 续接选文件夹，一个弹层完成 */
    private void showDestDialog() {
        if (running) { Toast.makeText(this, "正在合并，等它跑完", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));

        // 选项一：新建文件夹的名字输入框
        final EditText nameEt = new EditText(this);
        nameEt.setText(destNewName);
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        nameEt.setPadding(dp(10), dp(9), dp(10), dp(9));
        nameEt.setEnabled(!appendMode);
        nameEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { destNewName = s.toString().trim(); }
        });
        box.addView(nameEt, new LinearLayout.LayoutParams(-1, -2));

        // 选项二：续接已有文件夹（先建两个选项卡再挂监听，互相引用）
        LinearLayout optAppend = optCard("续接已有文件夹", appendSummary());
        LinearLayout optNew = optCard("新建文件夹", "在 下载/vd/图片合并/ 下新建（重跑同名先清空）");
        optNew.setOnClickListener(v -> {
            appendMode = false;
            paintOpt(optNew, true);
            paintOpt(optAppend, false);
            nameEt.setEnabled(true);
            refreshAll();
        });
        box.addView(optNew, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = (TextView) optAppend.getTag();
        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);
        pickRow.setPadding(dp(10), dp(8), dp(10), dp(2));
        pickRow.setEnabled(appendMode);
        TextView pickTx = new TextView(this);
        pickTx.setText(ownRel != null || treeUri != null ? "更换文件夹 ›" : "选择文件夹 ›");
        pickTx.setTextSize(13);
        pickTx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pickTx.setTextColor(0xFF3358D4);
        pickRow.addView(pickTx, new LinearLayout.LayoutParams(-2, -2));
        pickRow.setOnClickListener(v -> { if (appendMode) showDirDialog(() -> { sub.setText(appendSummary()); refreshAll(); }); });
        optAppend.addView(pickRow, new LinearLayout.LayoutParams(-1, -2));
        optAppend.setOnClickListener(v -> {
            appendMode = true;
            paintOpt(optNew, false);
            paintOpt(optAppend, true);
            nameEt.setEnabled(false);
            if (ownRel == null && treeUri == null) showDirDialog(() -> { sub.setText(appendSummary()); refreshAll(); });
            else refreshAll();
        });
        paintOpt(optNew, !appendMode);
        paintOpt(optAppend, appendMode);
        box.addView(optAppend, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
            .setTitle("保存到哪？")
            .setView(box)
            .setPositiveButton("完成", (d, w) -> refreshAll())
            .show();
    }

    private String appendSummary() {
        if (ownRel != null) return "续接「" + ownRel + "」· 现有 " + Math.max(0, basePreview) + " 张，按最大编号接着编";
        if (treeUri != null) return "续接「" + (destName.isEmpty() ? "所选文件夹" : destName) + "」";
        return "把新图片接在已有文件夹现有图片之后";
    }

    /** 二选一选项卡：选中态蓝描边高亮 */
    private LinearLayout optCard(String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1C2B4A);
        c.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(0xFF8A97B4);
        s.setLineSpacing(0, 1.4f);
        s.setPadding(0, dp(3), 0, 0);
        c.addView(s, new LinearLayout.LayoutParams(-2, -2));
        c.setTag(s);
        return c;
    }

    private void paintOpt(LinearLayout c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? 0xFFEEF2FF : 0xFFF6F8FC);
        g.setCornerRadius(dp(11));
        g.setStroke(on ? dp(1.6f) : dp(1), on ? 0xFF4F7CFF : 0xFFE3E8F1);
        c.setBackground(g);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    /** 回调式内置文件夹列表（选完通知对话框刷新文案） */
    private interface DirPickCb { void onPicked(); }

    /** 内置文件夹选择：列本工具图片合并目录下的子文件夹，一键选中；系统选择器仅兜底。
     *  列表查询放后台——真机 Downloads 集合大时前缀 LIKE 全表扫会有可感知卡顿 */
    private void showDirDialog(DirPickCb cb) {
        new Thread(() -> {
            final List<FolderInfo> fs = queryOwnedFolders();
            runOnUiThread(() -> { if (!isFinishing()) showDirList(fs, cb); });
        }).start();
    }

    private void showDirList(final List<FolderInfo> fs, final DirPickCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(2), dp(6), dp(2));
        final AlertDialog[] dlg = new AlertDialog[1];
        if (fs.isEmpty()) {
            TextView empty = smallTx("本工具还没合并过文件夹——可以用下面的入口从系统里选");
            empty.setPadding(dp(10), dp(12), dp(10), dp(12));
            box.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (FolderInfo f : fs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(0x00000000);
            rg.setCornerRadius(dp(10));
            row.setBackground(rg);
            LinearLayout iw = new LinearLayout(this);
            iw.setGravity(Gravity.CENTER);
            GradientDrawable ig = new GradientDrawable();
            ig.setColor(0xFFEEF2FF);
            ig.setCornerRadius(dp(9));
            iw.setBackground(ig);
            iw.addView(new FolderIco(this), new LinearLayout.LayoutParams(dp(22), dp(22)));
            row.addView(iw, new LinearLayout.LayoutParams(dp(36), dp(36)));
            LinearLayout txts = new LinearLayout(this);
            txts.setOrientation(LinearLayout.VERTICAL);
            txts.setPadding(dp(12), 0, 0, 0);
            TextView n1 = new TextView(this);
            n1.setText(f.name);
            n1.setTextSize(13.5f);
            n1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            n1.setTextColor(0xFF1C2B4A);
            txts.addView(n1, new LinearLayout.LayoutParams(-2, -2));
            TextView n2 = new TextView(this);
            n2.setText(f.mod > 0 ? getString(R.string.image_count_updated, f.count, df.format(new Date(f.mod))) : getString(R.string.image_count_unknown_date, f.count));
            n2.setTextSize(11f);
            n2.setTextColor(0xFF8A97B4);
            n2.setPadding(0, dp(3), 0, 0);
            txts.addView(n2, new LinearLayout.LayoutParams(-2, -2));
            row.addView(txts, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView rc = new TextView(this);
            rc.setText("›");
            rc.setTextSize(18);
            rc.setTextColor(0xFFB6C2DB);
            row.addView(rc, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                ownRel = f.name;
                treeUri = null;
                destName = f.name;
                basePreview = f.count;
                cb.onPicked();
            });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        View line = new View(this);
        line.setBackgroundColor(0xFFEDF0F5);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, dp(1));
        llp.topMargin = dp(6);
        llp.bottomMargin = dp(2);
        box.addView(line, llp);
        TextView sysBtn = new TextView(this);
        sysBtn.setText("从系统文件管理器选其他位置…");
        sysBtn.setTextSize(12.5f);
        sysBtn.setTextColor(0xFF3358D4);
        sysBtn.setPadding(dp(10), dp(12), dp(10), dp(12));
        sysBtn.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            pickDirExternal(cb);
        });
        box.addView(sysBtn, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this)
            .setTitle("续接到哪个文件夹？")
            .setView(sv)
            .setNegativeButton("取消", null)
            .show();
    }

    // ================= 选择压缩包 =================

    private void pickZips() {
        if (running) { Toast.makeText(this, "正在合并，等它跑完再选包", Toast.LENGTH_SHORT).show(); return; }
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("application/zip");
            it.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/octet-stream", "application/x-zip-compressed" });
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(it, PICK_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 兜底：外部文件夹走系统选择器（要授权），选完后台统计现有图片数给反馈 */
    private void pickDirExternal(DirPickCb cb) {
        try {
            pendingCb = cb;
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(it, PICK_DIR);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件夹选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private DirPickCb pendingCb;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == PICK_ZIP) {
            int before = picks.size();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    addPick(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addPick(data.getData());
            }
            if (picks.size() != before) previewPlans();
            else refreshAll();
        } else if (req == PICK_DIR && data.getData() != null) {
            treeUri = data.getData();
            ownRel = null;
            basePreview = -1;
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            DocumentFile df = DocumentFile.fromTreeUri(this, treeUri);
            destName = (df == null || df.getName() == null) ? "已选文件夹" : df.getName();
            refreshAll();
            final Uri fu = treeUri;
            new Thread(() -> {
                int n = 0;
                try {
                    DocumentFile t = DocumentFile.fromTreeUri(ZipMergeActivity.this, fu);
                    if (t != null) {
                        for (DocumentFile d : t.listFiles()) {
                            String nm = d.getName();
                            if (d.isFile() && nm != null && isImage(nm)) n++;
                        }
                    }
                } catch (Exception ignored) {}
                final int fn = n;
                runOnUiThread(() -> {
                    if (!fu.equals(treeUri)) return;   // 期间又换过文件夹就别覆盖
                    basePreview = fn;
                    if (fn == 0) Toast.makeText(this, "这个文件夹里没有图片——注意选的是直接放图片的那一层", Toast.LENGTH_LONG).show();
                    refreshAll();
                    if (pendingCb != null) { DirPickCb cb = pendingCb; pendingCb = null; cb.onPicked(); }
                });
            }).start();
        }
    }

    private void addPick(Uri uri) {
        for (ZipPick p : picks) if (p.uri.equals(uri)) return;
        ZipPick p = new ZipPick();
        p.uri = uri;
        String[] meta = queryMeta(uri);   // {DISPLAY_NAME, LAST_MODIFIED, SIZE}
        p.name = meta[0] == null || meta[0].isEmpty() ? "zip" : meta[0];
        try { p.modified = meta[1] == null ? 0 : Long.parseLong(meta[1]); } catch (Exception e) { p.modified = 0; }
        try { p.size = meta[2] == null ? 0 : Long.parseLong(meta[2]); } catch (Exception e) { p.size = 0; }
        picks.add(p);
    }

    /** SAF 文档的显示名 + 修改时间 + 大小（uri.getLastPathSegment() 只是文档 ID，不能当名字给人看） */
    private String[] queryMeta(Uri uri) {
        String[] out = new String[3];
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                new String[] { android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                               android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                               android.provider.DocumentsContract.Document.COLUMN_SIZE },
                null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        out[0] = c.getString(0);
                        out[1] = c.getString(1);
                        out[2] = c.getString(2);
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 选完压缩包后台预读各包清单，界面直接给出总张数和编号区间（敢确认，不用猜）。
     *  快照在 UI 线程拷好再交给后台（线程里拷会与下一次选包的 add 并发） */
    private void previewPlans() {
        final int gen = ++previewGen;
        refreshAll();
        final List<ZipPick> snap = new ArrayList<>(picks);
        new Thread(() -> {
            for (ZipPick p : snap) {
                if (p.plan != null || p.planFailed) continue;
                try { p.plan = planZip(p); } catch (Exception e) { p.planFailed = true; }
                if (gen != previewGen) return;
                runOnUiThread(this::refreshAll);
            }
        }).start();
    }

    /** 本工具图片合并目录下有哪些子文件夹（按最近更新排），供内置选择器列出 */
    private List<FolderInfo> queryOwnedFolders() {
        if (android.os.Build.VERSION.SDK_INT < 29) return new ArrayList<>();
        Map<String, FolderInfo> map = new HashMap<>();
        try {
            android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                               MediaStore.MediaColumns.DATE_MODIFIED },
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[] { OWN_ROOT + "%" }, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String rel = c.getString(0);
                        String name = c.getString(1);
                        long mod = c.getLong(2);
                        if (rel == null || name == null || !rel.startsWith(OWN_ROOT)) continue;
                        String s = rel.substring(OWN_ROOT.length());
                        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                        if (s.isEmpty() || s.contains("/")) continue;   // 只列直接子文件夹
                        if (!isImage(name)) continue;
                        FolderInfo f = map.get(s);
                        if (f == null) { f = new FolderInfo(); f.name = s; map.put(s, f); }
                        f.count++;
                        if (mod > f.mod) f.mod = mod;
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        List<FolderInfo> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.mod, a.mod));
        return out;
    }

    // ================= 核心：规划 + 提取 =================

    /** 自然排序键（与桌面版一致）：数字段按数值比（2 < 10），'+' 当番外排在整数之后 */
    private static int naturalCompare(String a, String b) {
        List<String> pa = splitParts(a), pb = splitParts(b);
        int n = Math.min(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            String sa = pa.get(i), sb = pb.get(i);
            boolean da = isAllDigits(sa), db = isAllDigits(sb);
            int c;
            if (da && db) {
                String ta = sa.replaceFirst("^0+", ""), tb = sb.replaceFirst("^0+", "");
                if (ta.isEmpty()) ta = "0";
                if (tb.isEmpty()) tb = "0";
                if (ta.length() != tb.length()) c = Integer.compare(ta.length(), tb.length());
                else c = ta.compareTo(tb);
            } else {
                c = sa.replace('+', '\uFFFF').replace('＋', '\uFFFF')
                     .toLowerCase(Locale.ROOT)
                     .compareTo(sb.replace('+', '\uFFFF').replace('＋', '\uFFFF').toLowerCase(Locale.ROOT));
            }
            if (c != 0) return c;
        }
        return Integer.compare(pa.size(), pb.size());
    }

    private static List<String> splitParts(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = DIGIT_SPLIT.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(s.substring(last, m.start()));
            out.add(m.group(1));
            last = m.end();
        }
        if (last < s.length()) out.add(s.substring(last));
        return out;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static boolean isImage(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXTS) if (low.endsWith(e)) return true;
        return false;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i).toLowerCase(Locale.ROOT) : ".jpg";
    }

    private static String mimeOf(String name) {
        String e = extOf(name);
        if (".png".equals(e)) return "image/png";
        if (".gif".equals(e)) return "image/gif";
        if (".bmp".equals(e)) return "image/bmp";
        if (".webp".equals(e)) return "image/webp";
        if (".tif".equals(e) || ".tiff".equals(e)) return "image/tiff";
        return "image/jpeg";   // jpg/jpeg/jfif/avif 统一走 jpeg 槽（系统相册都能认）
    }

    /** 读 zip 里全部图片条目名，按自然排序。中文乱码自动回退 GBK 重读 */
    private ZipPlan planZip(ZipPick p) throws Exception {
        InputStream is = getContentResolver().openInputStream(p.uri);
        if (is == null) throw new Exception("读不到这个文件");
        Charset cs = Charset.forName("UTF-8");
        List<String> names = collectImageNames(is, cs);
        if (hasMojibake(names)) {   // 非 UTF-8 编码的包：按 GBK 重读
            cs = Charset.forName("GBK");
            is = getContentResolver().openInputStream(p.uri);
            if (is == null) throw new Exception("读不到这个文件");
            names = collectImageNames(is, cs);
        }
        java.util.Collections.sort(names, ZipMergeActivity::naturalCompare);
        ZipPlan plan = new ZipPlan();
        plan.names = names;
        plan.charset = cs;
        return plan;
    }

    private List<String> collectImageNames(InputStream is, Charset cs) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(is, cs)) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                if (n.contains("__MACOSX") || n.contains(".DS_Store")) continue;
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (!base.isEmpty() && isImage(base)) names.add(base);
            }
        }
        return names;
    }

    private static boolean hasMojibake(List<String> names) {
        for (String n : names) if (n.contains("\uFFFD")) return true;
        return false;
    }

    private void startMerge() {
        if (running) return;
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "需要 Android 10 及以上系统", Toast.LENGTH_SHORT).show();
            return;
        }
        if (picks.isEmpty()) { Toast.makeText(this, "先点「压缩包」选 zip 文件", Toast.LENGTH_SHORT).show(); return; }
        final String fname;
        if (appendMode) {
            if (ownRel == null && treeUri == null) {
                Toast.makeText(this, "先在「保存到」里选一个要续接的文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            fname = "";
        } else {
            fname = destNewName;
            if (fname.isEmpty()) { Toast.makeText(this, "先给新文件夹起个名字", Toast.LENGTH_SHORT).show(); return; }
            if (fname.matches(".*[/\\\\<>:\"|?*].*")) { Toast.makeText(this, "文件夹名别带 / \\ : * ? \" < > |", Toast.LENGTH_SHORT).show(); return; }
        }
        running = true;
        ctaBtn.setEnabled(false);
        ctaBtn.setAlpha(0.45f);
        prog.setVisibility(View.VISIBLE);
        prog.setIndeterminate(true);
        statusTx.setVisibility(View.VISIBLE);
        statusTx.setText("正在读取压缩包…");
        resetBtn.setVisibility(View.GONE);
        new Thread(() -> {
            String err = null;
            try {
                // 1) 规划：每个包读一遍图片清单（自然排序，预读过的直接复用），算总数定编号宽度
                List<ZipPlan> plans = new ArrayList<>();
                int newTotal = 0;
                for (ZipPick p : picks) {
                    ZipPlan plan = p.plan;
                    if (plan == null) {
                        try { plan = planZip(p); }
                        catch (Exception e) { throw new Exception("「" + p.name + "」读不了：" + e.getMessage()); }
                        p.plan = plan;
                    }
                    plans.add(plan);
                    newTotal += plan.names.size();
                }
                if (newTotal == 0) {
                    runOnUiThread(() -> { running = false; unlockUi(); prog.setVisibility(View.GONE); statusTx.setText(R.string.zip_no_images); });
                    return;
                }
                // 续接从最大数字编号后开始，避免删过中间图片后覆盖末尾的旧图。
                // 现有文件一次性预载建索引——SAF 的 findFile 每写一张就全目录列举+逐条跨进程查询
                // （O(N²)，文件夹越大越慢，159 张续接实测十几分钟），必须先建索引再写
                int base = 0;
                Dest dest = new Dest();
                if (appendMode) {
                    if (ownRel != null) {
                        dest.sub = ownRel;
                        dest.msNames.addAll(queryNamesMedia(ownRel));
                        base = appendBase(dest.msNames);
                    } else {
                        dest.tree = DocumentFile.fromTreeUri(ZipMergeActivity.this, treeUri);
                        if (dest.tree == null || !dest.tree.exists()) throw new Exception("所选文件夹读不到，重新选一下");
                        for (DocumentFile d : dest.tree.listFiles()) {
                            String nm = d.getName();
                            if (nm != null && isImage(nm)) dest.treeFiles.put(nm, d);
                        }
                        base = appendBase(dest.treeFiles.keySet());
                    }
                } else {
                    dest.sub = fname;
                }
                final int total = Math.addExact(base, newTotal);
                final int width = Math.max(2, String.valueOf(total).length());
                // 2) 新文件夹模式：清空同名旧文件夹（桌面版同款行为：重跑先清掉旧结果）
                if (!appendMode) clearFolder(fname);
                // 3) 提取：压缩包按时间顺序，包内按名称排序，编号连续跨包（offset 是全局已编数量）
                final int ftotal = newTotal;
                final int fbase = base;
                runOnUiThread(() -> { prog.setIndeterminate(false); prog.setMax(ftotal); });
                int offset = base;
                for (int i = 0; i < picks.size(); i++) {
                    ZipPlan plan = plans.get(i);
                    if (plan.names.isEmpty()) continue;
                    extractZip(picks.get(i), plan, width, offset, dest, ftotal, fbase);
                    offset += plan.names.size();
                }
                final String destTxt = appendMode
                    ? (ownRel != null ? "下载/vd/图片合并/" + ownRel + "/"
                                      : (destName.isEmpty() ? "所选文件夹" : destName + "/"))
                    : "下载/vd/图片合并/" + fname + "/";
                final int nowCount = (appendMode && ownRel != null) ? queryNamesMedia(ownRel).size() : 0;
                runOnUiThread(() -> {
                    running = false;
                    unlockUi();
                    prog.setVisibility(View.GONE);
                    statusTx.setText(getString(R.string.zip_complete, ftotal, fbase + 1, total, destTxt));
                    statusTx.setTextColor(0xFF1F8A4C);
                    Toast.makeText(ZipMergeActivity.this, "已合并 " + ftotal + " 张图片", Toast.LENGTH_LONG).show();
                    resetBtn.setVisibility(View.VISIBLE);
                    // 续接完成后刷新内置列表里的张数
                    if (appendMode && ownRel != null) basePreview = nowCount;
                    refreshAll();
                });
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fe = err;
            runOnUiThread(() -> {
                running = false;
                unlockUi();
                prog.setVisibility(View.GONE);
                if (fe != null) {
                    statusTx.setText(getString(R.string.operation_error, fe));
                    statusTx.setTextColor(0xFFC0392B);
                    Toast.makeText(ZipMergeActivity.this, "出错了：" + fe, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void unlockUi() {
        ctaBtn.setEnabled(true);
        ctaBtn.setAlpha(1f);
        refreshAll();
    }

    private static int appendBase(java.util.Collection<String> names) {
        int base = names.size();
        for (String name : names) {
            String stem = name.substring(0, name.lastIndexOf('.'));
            if (!stem.matches("[0-9]+")) continue;
            try {
                base = Math.max(base, Integer.parseInt(stem));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("已有图片编号过大，无法续接");
            }
        }
        return base;
    }

    /** 按自然排序后的名单逐个提取：stream 顺序走一遍，命中一个写一个（编号已定，写入顺序无所谓）。
     *  同名条目（不同子目录）各自领号：编号按排序名单顺序派发，队列按出现顺序取走。
     *  offset = 全局已编号数量（跨压缩包连续编号从这里继续）；dest.tree 非空 = 外部文件夹走 SAF，
     *  否则按 RELATIVE_PATH 写 MediaStore（dest.sub = 新文件夹名或续接的子文件夹名） */
    private void extractZip(ZipPick p, ZipPlan plan, int width, int offset, Dest dest,
                            int total, int base) throws Exception {
        Map<String, java.util.ArrayDeque<Integer>> seq = new HashMap<>();
        int n = offset;
        for (String name : plan.names) {
            java.util.ArrayDeque<Integer> q = seq.get(name);
            if (q == null) { q = new java.util.ArrayDeque<>(); seq.put(name, q); }
            q.add(n++);
        }
        InputStream is = getContentResolver().openInputStream(p.uri);
        if (is == null) throw new Exception("读不到压缩包 " + p.name);
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(is, plan.charset)) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nm = e.getName().replace('\\', '/');
                if (nm.contains("__MACOSX") || nm.contains(".DS_Store")) continue;
                String baseName = nm.substring(nm.lastIndexOf('/') + 1);
                java.util.ArrayDeque<Integer> q = seq.get(baseName);
                if (q == null || q.isEmpty()) continue;
                Integer idx = q.poll();
                String outName = String.format(Locale.ROOT, "%0" + width + "d", idx + 1) + extOf(baseName);
                if (dest.tree != null) writeToTree(dest, outName, mimeOf(baseName), zin);
                else {
                    // 续接撞到旧同名（编号范围外的手动文件）才删，防系统 insert 自动加 " (1)" 后缀
                    if (dest.msNames.remove(outName)) deleteIfExists(OWN_ROOT + dest.sub, outName);
                    writeToDownloads("图片合并/" + dest.sub, outName, mimeOf(baseName), zin);
                }
                final int d = idx + 1 - base;
                runOnUiThread(() -> { prog.setProgress(d); statusTx.setText(getString(R.string.zip_extract_progress, d, total, p.name)); });
            }
        }
    }

    /** 外部文件夹（SAF）：写进所选文件夹。同名先删（防系统自动改名 xx (1).jpg）——
     *  现有文件从 dest.treeFiles 索引拿（开始时一次性 listFiles 建好）；
     *  绝不能在这里 findFile：它会把整个文件夹重列一遍、每条一次跨进程查询，O(N²) */
    private void writeToTree(Dest dest, String name, String mime, InputStream in) throws Exception {
        DocumentFile old = dest.treeFiles.remove(name);
        if (old != null) old.delete();
        DocumentFile doc = dest.tree.createFile(mime, name);
        if (doc == null) throw new Exception("写不进所选文件夹：" + name + "（试试换个文件夹）");
        dest.treeFiles.put(name, doc);
        try (OutputStream os = getContentResolver().openOutputStream(doc.getUri())) {
            if (os == null) throw new Exception("写入失败：" + name);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            doc.delete();
            dest.treeFiles.remove(name);
            throw e;
        }
    }

    /** 删除同名旧文件夹里的存量（桌面版重跑先清空；只清自己的子目录） */
    private void clearFolder(String folder) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.MediaColumns._ID },
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[] { OWN_ROOT + folder + "/" }, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        try {
                            getContentResolver().delete(Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0))), null, null);
                        } catch (Exception ignored) {}
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
    }

    /** 续接写 MediaStore 前先删同名文件（MediaStore insert 撞名会自动加 " (1)"，先删掉旧的） */
    private void deleteIfExists(String relDir, String name) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.MediaColumns._ID },
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[] { relDir.endsWith("/") ? relDir : relDir + "/", name }, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        try {
                            getContentResolver().delete(Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0))), null, null);
                        } catch (Exception ignored) {}
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
    }

    /** 一次性拉出自家某个合并文件夹里全部图片名：开始续接时既当现有计数（编号起点）也当同名索引 */
    private java.util.HashSet<String> queryNamesMedia(String sub) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        if (android.os.Build.VERSION.SDK_INT < 29) return out;
        try {
            android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.MediaColumns.DISPLAY_NAME },
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[] { OWN_ROOT + sub + "/" }, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String nm = c.getString(0);
                        if (nm != null && isImage(nm)) out.add(nm);
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void writeToDownloads(String sub, String name, String mime, InputStream in) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("需要 Android 10 及以上系统");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/vd/" + sub);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("创建文件失败：" + name);
        try {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("写入失败：" + name);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
        } catch (Exception e) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ================= 小件 =================

    /** 自绘文件夹线条小图（与 MenuIco 同风格：品牌蓝线条、圆头） */
    private static class FolderIco extends View {
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path folderPath = new android.graphics.Path();
        FolderIco(android.content.Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeWidth(1.7f * d);
            pt.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            pt.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            pt.setColor(0xFF3358D4);
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            android.graphics.Path ph = folderPath;
            ph.rewind();
            ph.moveTo(w * .16f, h * .36f);
            ph.lineTo(w * .34f, h * .36f);
            ph.lineTo(w * .42f, h * .26f);
            ph.lineTo(w * .60f, h * .26f);
            ph.lineTo(w * .66f, h * .33f);
            cv.drawPath(ph, pt);
            cv.drawRoundRect(w * .16f, h * .34f, w * .84f, h * .80f, h * .09f, h * .09f, pt);
        }
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(14), dp(12), dp(14), 0);
        return lp;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }
    private TextView smallTx(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(11.5f);
        v.setTextColor(0xFF8A97B4);
        v.setLineSpacing(0, 1.6f);
        return v;
    }
    private TextView pillBtn(String t, boolean outline) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(outline ? 0xFF4F7CFF : 0xFFFFFFFF);
        v.setTextSize(14.5f);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        if (outline) { g.setColor(0x00000000); g.setStroke(dp(1.4f), 0xFF4F7CFF); }
        else g.setColor(0xFF4F7CFF);
        g.setCornerRadius(dp(24));
        v.setBackground(g);
        v.setPadding(dp(20), dp(13), dp(20), dp(13));
        return v;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
