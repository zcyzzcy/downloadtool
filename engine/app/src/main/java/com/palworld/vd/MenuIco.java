package com.palworld.vd;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * v2.02 单色线条图标（v2.05 从 BrowserLayer 提出来共用）：emoji 每台手机长得都不一样，
 * 还跟浅色磁贴打架，所以全部自绘。以中心为原点的 ±12 坐标系内绘制，
 * 圆头线帽、品牌蓝描边（可 setColor）。浏览器菜单/底部栏、五个工具页共用一套。
 */
class MenuIco extends View {
    static final int IC_DL = 0, IC_SNIFF = 1, IC_TABS = 2, IC_PLUS = 3, IC_BM = 4, IC_CLOCK = 5,
        IC_STAR = 6, IC_SEARCH = 7, IC_MONI = 8, IC_COPY = 9, IC_SHARE = 10, IC_REFR = 11,
        IC_BACK = 12, IC_GEAR = 13, IC_MENU = 14, IC_HOME = 15, IC_QR = 16;
    // v2.05 工具页新字形：二维码 Tab / 视频工具五行 / 首页工具卡（Java 侧的呈现与 HTML SVG 同款）
    static final int IC_SCAN = 17, IC_GEN = 18, IC_ID = 19, IC_FILM = 20, IC_WAND = 21,
        IC_DEDUPE = 22, IC_SHRINK = 23, IC_NOTE = 24, IC_SCISS = 25, IC_PHOTO = 26, IC_FF = 27;

    // 磁贴底色（浏览器菜单同款轮换，工具页复用保持一家人的观感）
    static final int[] TINTS = { 0xFFE8EEFB, 0xFFFDE8EC, 0xFFE6F4EA, 0xFFFFF3E0, 0xFFEDE7F6 };

    private final int kind;
    private int color = 0xFF3358D4;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path iconPath = new android.graphics.Path();
    private final android.graphics.RectF iconRect = new android.graphics.RectF();
    private final Paint dashedPaint = new Paint();
    private final android.graphics.DashPathEffect dashEffect = new android.graphics.DashPathEffect(new float[]{3f, 2.5f}, 0);
    MenuIco(Context c, int kind) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }
    void setColor(int c) { if (color != c) { color = c; invalidate(); } }
    @Override protected void onDraw(Canvas cv) {
        float u = Math.min(getWidth(), getHeight()) / 24f;
        if (u <= 0) return;
        cv.save();
        cv.translate(getWidth() / 2f, getHeight() / 2f);
        cv.scale(u, u);
        p.setStrokeWidth(1.9f);
        p.setColor(color);
        switch (kind) {
            case IC_DL:   // 下载：竖箭头 + 托盘
                cv.drawLine(0, -8.5f, 0, 4, p);
                cv.drawLine(-4, 0, 0, 4, p);
                cv.drawLine(4, 0, 0, 4, p);
                cv.drawLine(-8, 7, -6.5f, 10, p);
                cv.drawLine(-6.5f, 10, 6.5f, 10, p);
                cv.drawLine(6.5f, 10, 8, 7, p);
                break;
            case IC_SNIFF:   // 嗅探：准星
                cv.drawCircle(0, 0, 6.2f, p);
                cv.drawLine(0, -10, 0, -7.8f, p);
                cv.drawLine(0, 10, 0, 7.8f, p);
                cv.drawLine(-10, 0, -7.8f, 0, p);
                cv.drawLine(10, 0, 7.8f, 0, p);
                cv.drawPoint(0, 0, p);
                break;
            case IC_TABS:   // 标签页：两个叠角圆角矩形
                cv.drawRoundRect(rr(-8.5f, -8.5f, 15, 15), 2f, 2f, p);
                cv.drawRoundRect(rr(-4, -4, 15, 15), 2f, 2f, p);
                break;
            case IC_PLUS:
                cv.drawLine(0, -6.5f, 0, 6.5f, p);
                cv.drawLine(-6.5f, 0, 6.5f, 0, p);
                break;
            case IC_BM: {   // 书签
                android.graphics.Path pt = iconPath;
                pt.rewind();
                pt.moveTo(-5, -8.5f); pt.lineTo(-5, 9.5f); pt.lineTo(0, 4.5f); pt.lineTo(5, 9.5f); pt.lineTo(5, -8.5f);
                cv.drawPath(pt, p);
                break;
            }
            case IC_CLOCK:
                cv.drawCircle(0, 0, 7.5f, p);
                cv.drawLine(0, 0, 0, -4.8f, p);
                cv.drawLine(0, 0, 3.2f, 1.6f, p);
                break;
            case IC_STAR: {   // 五角星
                android.graphics.Path pt = iconPath;
                pt.rewind();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 8.2f : 3.6f;
                    float x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r);
                    if (i == 0) pt.moveTo(x, y); else pt.lineTo(x, y);
                }
                pt.close();
                cv.drawPath(pt, p);
                break;
            }
            case IC_SEARCH:
                cv.drawCircle(-2.4f, -2.4f, 6f, p);
                cv.drawLine(2.2f, 2.2f, 8.5f, 8.5f, p);
                break;
            case IC_MONI:   // 显示器
                cv.drawRoundRect(rr(-9, -7.5f, 18, 12), 1.6f, 1.6f, p);
                cv.drawLine(0, 4.5f, 0, 7.5f, p);
                cv.drawLine(-4.5f, 7.5f, 4.5f, 7.5f, p);
                break;
            case IC_COPY:   // 复制：两个叠角方块
                cv.drawRoundRect(rr(-8.5f, -8.5f, 13, 13), 1.6f, 1.6f, p);
                cv.drawRoundRect(rr(-4, -4, 13, 13), 1.6f, 1.6f, p);
                break;
            case IC_SHARE:   // 分享：上箭头 + 底盒
                cv.drawRoundRect(rr(-7, -1.5f, 14, 10), 1.6f, 1.6f, p);
                cv.drawLine(0, 2.5f, 0, -9, p);
                cv.drawLine(-3.6f, -5.6f, 0, -9.2f, p);
                cv.drawLine(3.6f, -5.6f, 0, -9.2f, p);
                break;
            case IC_REFR: {   // 刷新：圆弧 + 箭头
                android.graphics.RectF oval = rr(-7, -7, 14, 14);
                cv.drawArc(oval, -200, 290, false, p);
                cv.drawLine(-3.2f, 5.2f, 0.4f, 8.6f, p);
                cv.drawLine(3.8f, 5.0f, 0.4f, 8.6f, p);
                break;
            }
            case IC_BACK:   // 后退：左尖括号
                cv.drawLine(5, -7, -4, 0, p);
                cv.drawLine(-4, 0, 5, 7, p);
                break;
            case IC_GEAR:   // 设置：圆 + 八齿
                cv.drawCircle(0, 0, 3.4f, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    cv.drawLine((float) Math.cos(a) * 5.6f, (float) Math.sin(a) * 5.6f,
                        (float) Math.cos(a) * 8.4f, (float) Math.sin(a) * 8.4f, p);
                }
                break;
            case IC_MENU:   // ≡ 三横
                cv.drawLine(-6.5f, -4.5f, 6.5f, -4.5f, p);
                cv.drawLine(-6.5f, 0, 6.5f, 0, p);
                cv.drawLine(-6.5f, 4.5f, 6.5f, 4.5f, p);
                break;
            case IC_HOME: {   // 首页：小房子
                android.graphics.Path pt2 = iconPath;
                pt2.rewind();
                pt2.moveTo(-7.5f, -0.5f); pt2.lineTo(0, -8); pt2.lineTo(7.5f, -0.5f);
                cv.drawPath(pt2, p);
                cv.drawRoundRect(rr(-5, -0.5f, 10, 9), 1.2f, 1.2f, p);
                break;
            }
            case IC_QR: {   // 二维码：三个定位角 + 中部点阵
                cv.drawRoundRect(rr(-9, -9, 6, 6), 1f, 1f, p);
                cv.drawRoundRect(rr(-9, 3, 6, 6), 1f, 1f, p);
                cv.drawRoundRect(rr(3, -9, 6, 6), 1f, 1f, p);
                cv.drawPoint(0, 0, p);
                cv.drawPoint(3, 1.5f, p);
                cv.drawPoint(0, 4f, p);
                cv.drawPoint(4f, -3.5f, p);
                cv.drawPoint(-3.5f, 0f, p);
                cv.drawPoint(6f, 4f, p);
                break;
            }
            case IC_SCAN: {   // 扫码识别：四角取景框 + 扫描线
                android.graphics.Path fr = iconPath;
                fr.rewind();
                fr.moveTo(-9, -4); fr.lineTo(-9, -9); fr.lineTo(-4, -9);
                fr.moveTo(4, -9); fr.lineTo(9, -9); fr.lineTo(9, -4);
                fr.moveTo(9, 4); fr.lineTo(9, 9); fr.lineTo(4, 9);
                fr.moveTo(-4, 9); fr.lineTo(-9, 9); fr.lineTo(-9, 4);
                cv.drawPath(fr, p);
                cv.drawLine(-5.5f, 0, 5.5f, 0, p);
                break;
            }
            case IC_GEN:   // 生成：定位角 + 加号
                cv.drawRoundRect(rr(-9.5f, -9.5f, 5.5f, 5.5f), 1f, 1f, p);
                cv.drawRoundRect(rr(-9.5f, 4, 5.5f, 5.5f), 1f, 1f, p);
                cv.drawRoundRect(rr(4, -9.5f, 5.5f, 5.5f), 1f, 1f, p);
                cv.drawLine(6.5f, 4, 6.5f, 9, p);
                cv.drawLine(4, 6.5f, 9, 6.5f, p);
                break;
            case IC_ID:   // 证件照：取景框里的人像
                cv.drawRoundRect(rr(-9, -9, 18, 18), 2f, 2f, p);
                cv.drawCircle(0, -3.4f, 2.5f, p);
                cv.drawArc(rr(-5, 1, 10, 7.5f), 180, 180, false, p);
                break;
            case IC_FILM: {   // 视频：圆角屏 + 播放键
                cv.drawRoundRect(rr(-9.5f, -7, 19, 14), 2f, 2f, p);
                android.graphics.Path pl = iconPath;
                pl.rewind();
                pl.moveTo(-2.2f, -3.6f); pl.lineTo(-2.2f, 3.6f); pl.lineTo(4, 0); pl.close();
                cv.drawPath(pl, p);
                break;
            }
            case IC_WAND: {   // 智能抠图：魔棒 + 星光
                cv.drawLine(-7.5f, 7.5f, 3.5f, -3.5f, p);
                cv.drawLine(7.5f, -9.8f, 7.5f, -5.2f, p);
                cv.drawLine(5.2f, -7.5f, 9.8f, -7.5f, p);
                cv.drawPoint(-6, -6.5f, p);
                cv.drawPoint(0.5f, 9, p);
                break;
            }
            case IC_DEDUPE:   // 去重：虚线影件（将被清掉的副本）+ 实体件
                cv.drawRoundRect(rr(-8.5f, -8.5f, 11.5f, 11.5f), 2f, 2f, dashed());
                cv.drawRoundRect(rr(-3, -3, 11.5f, 11.5f), 2f, 2f, p);
                break;
            case IC_SHRINK:   // 压缩：上下向中间挤的两支箭头
                cv.drawLine(0, -9.5f, 0, -4, p);
                cv.drawLine(-3, -7, 0, -4, p);
                cv.drawLine(3, -7, 0, -4, p);
                cv.drawLine(0, 9.5f, 0, 4, p);
                cv.drawLine(-3, 7, 0, 4, p);
                cv.drawLine(3, 7, 0, 4, p);
                break;
            case IC_NOTE:   // 提取音频：八分音符（实心符头）
                p.setStyle(Paint.Style.FILL);
                cv.drawOval(rr(-4, 2.3f, 5.6f, 4.4f), p);
                p.setStyle(Paint.Style.STROKE);
                cv.drawLine(1.4f, 4.3f, 1.4f, -8, p);
                cv.drawLine(1.4f, -8, 6.2f, -5.4f, p);
                break;
            case IC_SCISS:   // 剪辑：剪刀（双环 + 交叉刀刃）
                cv.drawCircle(-7, -7, 2.3f, p);
                cv.drawCircle(-7, 7, 2.3f, p);
                cv.drawLine(-5, -5, 8.5f, 8.5f, p);
                cv.drawLine(-5, 5, 8.5f, -8.5f, p);
                break;
            case IC_PHOTO: {   // 转GIF：照片框（太阳 + 山）
                cv.drawRoundRect(rr(-9.5f, -7.5f, 19, 15), 2f, 2f, p);
                cv.drawCircle(-4.5f, -3.5f, 1.5f, p);
                android.graphics.Path mt = iconPath;
                mt.rewind();
                mt.moveTo(-6.5f, 5); mt.lineTo(-2.5f, -0.5f); mt.lineTo(0, 2); mt.lineTo(3, -2.5f); mt.lineTo(6.5f, 5);
                cv.drawPath(mt, p);
                break;
            }
            case IC_FF: {   // 倍速：双右尖括号
                android.graphics.Path c1 = iconPath;
                c1.rewind();
                c1.moveTo(-8, -5.5f); c1.lineTo(-2.5f, 0); c1.lineTo(-8, 5.5f);
                cv.drawPath(c1, p);
                android.graphics.Path c2 = iconPath;
                c2.rewind();
                c2.moveTo(0.5f, -5.5f); c2.lineTo(6, 0); c2.lineTo(0.5f, 5.5f);
                cv.drawPath(c2, p);
                break;
            }
        }
        cv.restore();
    }

    private Paint dashed() {   // 副本虚线：临时画笔（不动主画笔的路径效果状态）
        Paint d = dashedPaint;
        d.set(p);
        d.setPathEffect(dashEffect);
        return d;
    }
    private android.graphics.RectF rr(float l, float t, float w, float h) {
        iconRect.set(l, t, l + w, t + h);
        return iconRect;
    }
}
