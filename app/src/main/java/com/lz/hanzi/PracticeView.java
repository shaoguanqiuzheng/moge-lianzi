package com.lz.hanzi;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 练字画板：压感笔迹 + 田/米字格 + 参照字。
 *
 * 设计要点：
 *  - 已经落笔的笔迹渲染到一张与 View 等大的 Bitmap 上，绘制时只做一次 blit；
 *  - 书写过程中每收到一个点就增量画到 Bitmap，并只 invalidate 这一小块的脏矩形，
 *    避免整屏重绘，保证 M-Pencil 2（120Hz + 压感）跟手；
 *  - 撤销/重做用重建 Bitmap 的方式实现（笔迹数量少，代价可忽略）。
 */
public class PracticeView extends View {

    public interface Listener {
        /** 历史栈变化，用于刷新撤销/重做按钮 */
        void onHistoryChanged(boolean canUndo, boolean canRedo);

        /** 每次采样到一个笔迹点时回调，用于状态栏显示实时压感/宽度 */
        void onPenSample(float pressure, float width, int toolType);

        /** 笔顺判定结果：ok=这笔写对；expected=应该写的笔序(0基)；matched=最接近的笔序；done/total=进度 */
        void onJudgeResult(boolean ok, int expected, int matched, float distance, int done, int total);

        /** 格子行列数因尺寸变化而重排（行数会自动补满） */
        void onGridChanged(int cols, int rows);
    }

    public static final int REF_FAINT = 0;   // 描红（最浅）
    public static final int REF_GUIDE = 1;   // 示范（浅灰）
    public static final int REF_OFF = 2;     // 隐藏

    public static final int GRID_TIAN = 0;   // 田字格
    public static final int GRID_MI = 1;     // 米字格

    /** 一条完整笔迹：每个点存 {x, y, width} */
    private static class Stroke {
        final ArrayList<float[]> pts = new ArrayList<>(64);
        boolean eraser;
    }

    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridDashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private final Path segPath = new Path();

    private Bitmap ink;
    private Canvas inkCanvas;

    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Stroke> redoStack = new ArrayList<>();
    private Stroke current;

    /** 笔尖基准宽度（px），实际宽度再乘压感系数 */
    private float penWidth = 10f;
    private boolean eraser;

    private String refChar = "永";
    private int refMode = REF_FAINT;
    private int gridMode = GRID_TIAN;

    /** 当前页面的格子尺寸（正方形边长，px） */
    private float cell = 0f;
    /** 每个格子的左上角坐标，最多 4 列 × 4 行 */
    private final float[] cellX = new float[96];
    private final float[] cellY = new float[96];
    /** 每行格数（用户可选） */
    private int cellCols = 2;
    /** 行数：按剩余高度自动补满 */
    private int cellRows = 1;
    /** 格子大小系数（相对可用区域的最大尺寸），0.35~1.0 */
    private float sizeScale = 1f;

    private float lastX, lastY, lastW;
    private long lastT;
    private int activeTool = -1;

    /** 只允许手写笔输入（防手掌误触） */
    private boolean penOnly;
    private Listener listener;

    // ---------------------------------------------------------------- 笔顺提示 / 判定

    public static final int GUIDE_OFF = 0;
    public static final int GUIDE_HINT = 1;
    public static final int GUIDE_JUDGE = 2;

    /** 判定阈值：用户笔迹与参考中线的平均偏差（0..255 归一化，256 等于一格边长） */
    private static final float OK_DIST = 24f;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideText = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 画轮廓用的填充画笔（参照字 / 笔顺高亮 / 动画） */
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 归一化坐标(0..255)下的笔画轮廓路径，与中线同源，保证严格对齐 */
    private Path[] strokePaths;
    /** 把一个格子的字形预渲染成位图，多格页面上直接贴图，避免每帧重画几十份路径 */
    private Bitmap refBitmap;
    private final Paint refBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint refRenderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int guideMode = GUIDE_OFF;
    private StrokeOrder.Stroke[] guideStrokes;
    private int guideIndex;
    private int judgeOk;
    private int judgeTotal;
    private ValueAnimator animator;
    private float animProgress = -1f;

    public PracticeView(Context context) {
        this(context, null);
    }

    public PracticeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        bgPaint.setColor(0xFFFDFCF8);

        inkPaint.setStyle(Paint.Style.FILL);
        inkPaint.setColor(0xFF141414);

        eraserPaint.setStyle(Paint.Style.FILL);
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1.2f));
        gridPaint.setColor(0xFFB4BCC6);

        gridDashedPaint.setStyle(Paint.Style.STROKE);
        gridDashedPaint.setStrokeWidth(dp(1f));
        gridDashedPaint.setColor(0xFFC3CBD4);
        gridDashedPaint.setPathEffect(new DashPathEffect(new float[]{dp(9), dp(9)}, 0));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2f));
        borderPaint.setColor(0xFF6B7684);

        refPaint.setTextAlign(Paint.Align.CENTER);
        refPaint.setTypeface(loadGuideTypeface(context));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeCap(Paint.Cap.ROUND);
        guidePaint.setStrokeJoin(Paint.Join.ROUND);

        guideDot.setStyle(Paint.Style.FILL);

        guideText.setTextAlign(Paint.Align.CENTER);
        guideText.setFakeBoldText(true);

        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setAntiAlias(true);
        refRenderPaint.setStyle(Paint.Style.FILL);
        refRenderPaint.setColor(0xFF000000);
    }

    /** 只包含「没有笔顺数据的 129 个字」的楷体子集，用于极少数缺数据的字兜底 */
    private static Typeface loadGuideTypeface(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/kai_fallback.ttf");
        } catch (Throwable t) {
            Typeface kai = Typeface.create("kaiti", Typeface.NORMAL);
            return kai != null ? kai : Typeface.SERIF;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- 尺寸/位图

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        if (ink != null) ink.recycle();
        ink = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        inkCanvas = new Canvas(ink);
        layoutGrid(w, h);
        rebuildInk();
    }

    private void layoutGrid(int w, int h) {
        float pad = dp(22);
        float gap = dp(16);
        float availW = w - 2 * pad;
        float availH = h - 2 * pad;
        if (availW <= 0 || availH <= 0) {
            return;
        }

        // 目标格子边长：相对「单格能占的最大正方形」的 0.35~1.0
        float maxSide = Math.min(availW, availH);
        float target = maxSide * sizeScale;

        // 在「格子别比目标大太多、也别缩太多」的前提下，选覆盖率最高的行列数
        int bestCols = 1;
        int bestRows = 1;
        float bestSide = 0f;
        float bestCoverage = -1f;
        for (int c = 1; c <= 12; c++) {
            float sideC = (availW - gap * (c - 1)) / c;
            if (sideC <= 0) {
                break;
            }
            for (int r = 1; r <= 8; r++) {
                float sideR = (availH - gap * (r - 1)) / r;
                if (sideR <= 0) {
                    break;
                }
                float s = Math.min(sideC, sideR);
                if (s > target * 1.02f || s < target * 0.55f || s < dp(36)) {
                    continue;
                }
                float coverage = (c * r * s * s) / (availW * availH);
                boolean better = coverage > bestCoverage + 0.01f
                        || (Math.abs(coverage - bestCoverage) <= 0.01f
                        && Math.abs(s - target) < Math.abs(bestSide - target));
                if (better) {
                    bestCoverage = coverage;
                    bestSide = s;
                    bestCols = c;
                    bestRows = r;
                }
            }
        }
        if (bestSide <= 0f) {
            bestSide = Math.min(availW, availH) * sizeScale;
        }

        boolean gridChanged = (bestRows != cellRows || bestCols != cellCols);
        cellRows = bestRows;
        cellCols = bestCols;
        cell = bestSide;

        float totalW = bestSide * bestCols + gap * (bestCols - 1);
        float totalH = bestSide * bestRows + gap * (bestRows - 1);
        float startX = pad + (availW - totalW) / 2f;
        float startY = pad + (availH - totalH) / 2f;
        for (int r = 0; r < bestRows; r++) {
            for (int c = 0; c < bestCols; c++) {
                int i = r * bestCols + c;
                cellX[i] = startX + c * (bestSide + gap);
                cellY[i] = startY + r * (bestSide + gap);
            }
        }

        if (listener != null) {
            final int rc = bestRows;
            final int cc = bestCols;
            post(() -> listener.onGridChanged(cc, rc));
        }
    }

    private void rebuildInk() {
        if (inkCanvas == null) return;
        inkCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (Stroke s : strokes) renderStroke(s, inkCanvas);
        if (current != null) renderStroke(current, inkCanvas);
        invalidate();
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        drawGrid(canvas);
        drawRefChar(canvas);
        drawGuideOverlay(canvas);
        if (ink != null && !ink.isRecycled()) {
            canvas.drawBitmap(ink, 0, 0, null);
        }
    }

    private void drawGrid(Canvas canvas) {
        if (cell <= 0) return;
        int n = Math.max(1, Math.min(96, cellRows * cellCols));
        for (int i = 0; i < n; i++) {
            float l = cellX[i];
            float t = cellY[i];
            float r = l + cell;
            float b = t + cell;
            canvas.drawRect(l, t, r, b, borderPaint);

            float cx = l + cell / 2f;
            float cy = t + cell / 2f;
            canvas.drawLine(cx, t, cx, b, gridDashedPaint);
            canvas.drawLine(l, cy, r, cy, gridDashedPaint);

            if (gridMode == GRID_MI) {
                canvas.drawLine(l, t, r, b, gridDashedPaint);
                canvas.drawLine(r, t, l, b, gridDashedPaint);
            }
        }
    }

    private void drawRefChar(Canvas canvas) {
        if (refMode == REF_OFF || cell <= 0) {
            return;
        }
        int n = Math.max(1, Math.min(96, cellRows * cellCols));
        int color = refMode == REF_FAINT ? 0x3CC08A5E : 0x24000000;

        // 有笔顺数据时用同一份笔画轮廓渲染：字形与中线严格同源，不再有偏移
        if (strokePaths != null) {
            ensureRefBitmap();
            refBitmapPaint.setColor(color);
            for (int i = 0; i < n; i++) {
                canvas.drawBitmap(refBitmap, cellX[i], cellY[i], refBitmapPaint);
            }
            return;
        }

        // 极少数没有笔顺数据的字，退回字体渲染
        if (refChar == null || refChar.isEmpty()) {
            return;
        }
        refPaint.setTextSize(cell * 0.80f);
        refPaint.setColor(color);
        Paint.FontMetrics fm = refPaint.getFontMetrics();
        for (int i = 0; i < n; i++) {
            float cx = cellX[i] + cell / 2f;
            float baseline = cellY[i] + cell / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(refChar, cx, baseline, refPaint);
        }
    }

    // ---------------------------------------------------------------- 笔迹渲染

    /** 把一条笔迹画到指定 Canvas（重建时使用） */
    private void renderStroke(Stroke s, Canvas canvas) {
        if (s == null || s.pts.isEmpty()) return;
        Paint paint = s.eraser ? eraserPaint : inkPaint;

        float[] prev = s.pts.get(0);
        if (s.pts.size() == 1) {
            canvas.drawCircle(prev[0], prev[1], Math.max(0.7f, prev[2] * 0.5f), paint);
            return;
        }
        for (int i = 1; i < s.pts.size(); i++) {
            float[] p = s.pts.get(i);
            drawSegment(prev[0], prev[1], prev[2], p[0], p[1], p[2], canvas, paint);
            prev = p;
        }
    }

    /** 用四边形（两端宽度不同）+ 圆头，拼出有粗细变化的笔段 */
    private void drawSegment(float x0, float y0, float w0,
                             float x1, float y1, float w1,
                             Canvas canvas, Paint paint) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        float hw0 = Math.max(0.55f, w0 * 0.5f);
        float hw1 = Math.max(0.55f, w1 * 0.5f);

        if (len < 0.001f) {
            canvas.drawCircle(x1, y1, hw1, paint);
            return;
        }

        // 长段再细分，避免快速书写时粗细跳变
        int steps = (int) Math.min(12, Math.max(1, Math.ceil(len / Math.max(2f, w1))));
        float px = x0, py = y0, pw = hw0;
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            float nx = x0 + dx * t;
            float ny = y0 + dy * t;
            float nw = hw0 + (hw1 - hw0) * t;

            float sx = nx - px, sy = ny - py;
            float slen = (float) Math.hypot(sx, sy);
            if (slen > 0.001f) {
                float nX = -sy / slen;
                float nY = sx / slen;
                segPath.reset();
                segPath.moveTo(px + nX * pw, py + nY * pw);
                segPath.lineTo(nx + nX * nw, ny + nY * nw);
                segPath.lineTo(nx - nX * nw, ny - nY * nw);
                segPath.lineTo(px - nX * pw, py - nY * pw);
                segPath.close();
                canvas.drawPath(segPath, paint);
            }
            canvas.drawCircle(nx, ny, nw, paint);
            px = nx;
            py = ny;
            pw = nw;
        }
    }

    // ---------------------------------------------------------------- 触摸输入

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int tool = e.getToolType(0);

        if (action == MotionEvent.ACTION_DOWN) {
            if (inkCanvas == null) return false;
            if (penOnly && tool != MotionEvent.TOOL_TYPE_STYLUS) return false;
            stopAnimation();
            // 手写笔输入开启无缓冲调度，跳过系统的手势延迟
            if (tool == MotionEvent.TOOL_TYPE_STYLUS) {
                try {
                    requestUnbufferedDispatch(e);
                } catch (Throwable ignored) {
                }
            }
            beginStroke(e, tool);
            return true;
        }

        if (current == null) return false;

        // 笔正在写时忽略手指/手掌接触
        if (tool == MotionEvent.TOOL_TYPE_FINGER && activeTool == MotionEvent.TOOL_TYPE_STYLUS) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                int hs = e.getHistorySize();
                for (int i = 0; i < hs; i++) {
                    addPoint(e.getHistoricalX(0, i), e.getHistoricalY(0, i),
                            e.getHistoricalPressure(0, i), e.getHistoricalEventTime(i));
                }
                addPoint(e.getX(), e.getY(), e.getPressure(0), e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // 指针抬起：结束当前笔迹，交给后续 ACTION_UP
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                endStroke();
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void beginStroke(MotionEvent e, int tool) {
        activeTool = tool;
        current = new Stroke();
        current.eraser = eraser;

        lastX = e.getX();
        lastY = e.getY();
        lastW = widthFor(e.getPressure(0));
        lastT = e.getEventTime();
        current.pts.add(new float[]{lastX, lastY, lastW});

        notifySample(e.getPressure(0), lastW);
    }

    private void addPoint(float x, float y, float pressure, long time) {
        if (current == null || inkCanvas == null) return;

        float dx = x - lastX;
        float dy = y - lastY;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 0.7f) {
            return; // 抖动过滤
        }

        // 轻量低通平滑，减手抖
        float sx = lastX + dx * 0.72f;
        float sy = lastY + dy * 0.72f;

        float baseW = widthFor(pressure);

        long dt = Math.max(1L, time - lastT);
        float speed = dist / dt * 1000f; // px/s
        // 快写收细、慢写变粗，模拟笔锋
        float speedFactor = 1f - Math.min(0.34f, Math.max(0f, (speed - 350f) / 2800f));
        float w = clamp(baseW * speedFactor, penWidth * 0.28f, penWidth * 1.9f);

        Paint paint = current.eraser ? eraserPaint : inkPaint;
        drawSegment(lastX, lastY, lastW, sx, sy, w, inkCanvas, paint);

        current.pts.add(new float[]{sx, sy, w});

        // 只重绘这一小段
        float pad = Math.max(lastW, w) + dp(6);
        int l = (int) Math.floor(Math.min(lastX, sx) - pad);
        int t = (int) Math.floor(Math.min(lastY, sy) - pad);
        int r = (int) Math.ceil(Math.max(lastX, sx) + pad);
        int b = (int) Math.ceil(Math.max(lastY, sy) + pad);
        invalidate(l, t, r, b);

        lastX = sx;
        lastY = sy;
        lastW = w;
        lastT = time;

        notifySample(pressure, w);
    }

    private void endStroke() {
        if (current == null) return;
        Stroke finished = current;
        if (!current.pts.isEmpty()) {
            strokes.add(current);
            redoStack.clear();
        }
        current = null;
        activeTool = -1;
        if (finished != null && !finished.eraser) {
            if (guideMode == GUIDE_JUDGE) {
                judgeStroke(finished);
            } else if (guideMode == GUIDE_HINT && guideStrokes != null
                    && guideIndex < guideStrokes.length) {
                guideIndex++;
            }
        }
        notifyHistory();
        invalidate();
    }

    private void notifySample(float pressure, float width) {
        if (listener != null) {
            listener.onPenSample(pressure, width, activeTool);
        }
    }

    private void notifyHistory() {
        if (listener != null) {
            listener.onHistoryChanged(canUndo(), canRedo());
        }
    }

    private float widthFor(float pressure) {
        float pr = clamp(pressure, 0.02f, 1f);
        // 压感曲线：轻按细、重按粗
        float f = 0.30f + 0.95f * (float) Math.pow(pr, 0.75);
        float w = penWidth * f;
        if (eraser) w *= 2.4f;
        return w;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 笔顺：提示 / 动画 / 判定

    public void setGuideMode(int mode) {
        if (guideMode == mode) {
            return;
        }
        guideMode = mode;
        guideIndex = 0;
        judgeOk = 0;
        judgeTotal = 0;
        invalidate();
    }

    public int getGuideMode() {
        return guideMode;
    }

    public void setGuideStrokes(StrokeOrder.Stroke[] strokes) {
        guideStrokes = strokes;
        buildStrokePaths();
        refBitmap = null;
        guideIndex = 0;
        judgeOk = 0;
        judgeTotal = 0;
        stopAnimation();
        invalidate();
    }

    /** 预渲染一格的字形（ALPHA_8 只存透明度），多格页面直接贴图，省掉每帧几十份路径绘制 */
    private void ensureRefBitmap() {
        int size = Math.max(2, Math.round(cell));
        if (refBitmap != null && refBitmap.getWidth() == size) {
            return;
        }
        if (refBitmap != null) {
            refBitmap.recycle();
        }
        refBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(refBitmap);
        c.scale(size / 256f, size / 256f);
        for (Path p : strokePaths) {
            if (p != null) {
                c.drawPath(p, refRenderPaint);
            }
        }
    }

    /** 把笔画轮廓预先构建成归一化 Path，绘制时只做变换 */
    private void buildStrokePaths() {
        if (guideStrokes == null) {
            strokePaths = null;
            return;
        }
        Path[] paths = new Path[guideStrokes.length];
        for (int i = 0; i < guideStrokes.length; i++) {
            StrokeOrder.Stroke s = guideStrokes[i];
            Path p = new Path();
            int ci = 0;
            for (int c = 0; c < s.types.length; c++) {
                switch (s.types[c]) {
                    case 0:
                        p.moveTo(s.coords[ci], s.coords[ci + 1]);
                        ci += 2;
                        break;
                    case 1:
                        p.lineTo(s.coords[ci], s.coords[ci + 1]);
                        ci += 2;
                        break;
                    case 2:
                        p.quadTo(s.coords[ci], s.coords[ci + 1], s.coords[ci + 2], s.coords[ci + 3]);
                        ci += 4;
                        break;
                    case 3:
                        p.cubicTo(s.coords[ci], s.coords[ci + 1], s.coords[ci + 2], s.coords[ci + 3],
                                s.coords[ci + 4], s.coords[ci + 5]);
                        ci += 6;
                        break;
                    case 4:
                        p.close();
                        break;
                    default:
                        break;
                }
            }
            paths[i] = p;
        }
        strokePaths = paths;
    }

    public boolean hasGuide() {
        return guideStrokes != null && guideStrokes.length > 0;
    }

    public int guideIndex() {
        return guideIndex;
    }

    public int guideTotal() {
        return guideStrokes == null ? 0 : guideStrokes.length;
    }

    public int judgeOk() {
        return judgeOk;
    }

    public int judgeTotal() {
        return judgeTotal;
    }

    /** 逐笔播放笔顺动画 */
    public void playGuideAnimation() {
        if (guideStrokes == null || guideStrokes.length == 0) {
            return;
        }
        stopAnimation();
        final int n = guideStrokes.length;
        ValueAnimator a = ValueAnimator.ofFloat(0f, n);
        a.setDuration(Math.max(600L, n * 420L));
        a.addUpdateListener(anim -> {
            animProgress = (float) anim.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animProgress = -1f;
                invalidate();
            }
        });
        animator = a;
        a.start();
    }

    public void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        animProgress = -1f;
    }

    private void drawGuideOverlay(Canvas canvas) {
        if (strokePaths == null || guideStrokes == null || cell <= 0) {
            return;
        }
        int cells = Math.max(1, Math.min(96, cellRows * cellCols));

        if (animProgress >= 0) {
            int total = guideStrokes.length;
            int idx = Math.min(total - 1, (int) Math.floor(animProgress));
            float local = Math.min(1f, Math.max(0f, animProgress - idx));
            for (int ci = 0; ci < cells; ci++) {
                for (int i = 0; i <= idx; i++) {
                    boolean cur = (i == idx);
                    glyphPaint.setColor(cur ? 0xCC8C6239 : 0x992E2A25);
                    drawStrokeReveal(canvas, ci, guideStrokes[i], strokePaths[i], cur ? local : 1f);
                }
                drawPenHead(canvas, ci, guideStrokes[idx], local);
            }
            return;
        }

        if (guideMode == GUIDE_OFF || guideIndex >= guideStrokes.length) {
            return;
        }

        glyphPaint.setColor(0x668C6239);
        for (int ci = 0; ci < cells; ci++) {
            canvas.save();
            canvas.translate(cellX[ci], cellY[ci]);
            canvas.scale(cell / 256f, cell / 256f);
            canvas.drawPath(strokePaths[guideIndex], glyphPaint);
            canvas.restore();
            drawStrokeNumber(canvas, ci, guideIndex + 1, guideStrokes[guideIndex]);
        }
    }

    /** 用「沿中线推进的胶囊」裁剪轮廓，做出逐笔写出来的效果 */
    private void drawStrokeReveal(Canvas canvas, int ci, StrokeOrder.Stroke s, Path path, float progress) {
        canvas.save();
        canvas.translate(cellX[ci], cellY[ci]);
        canvas.scale(cell / 256f, cell / 256f);
        if (progress < 0.999f) {
            canvas.clipPath(capsulePath(s, progress));
        }
        canvas.drawPath(path, glyphPaint);
        canvas.restore();
    }

    /** 中线上已走部分扫过的胶囊区域（归一化坐标） */
    private Path capsulePath(StrokeOrder.Stroke s, float progress) {
        Path p = new Path();
        int n = s.points();
        if (n == 0) {
            return p;
        }
        float r = Math.max(3f, s.radius);
        float total = 0f;
        for (int i = 1; i < n; i++) {
            total += (float) Math.hypot(s.mx[i] - s.mx[i - 1], s.my[i] - s.my[i - 1]);
        }
        float limit = total * Math.max(0f, Math.min(1f, progress));

        p.addCircle(s.mx[0], s.my[0], r, Path.Direction.CW);

        float acc = 0f;
        for (int i = 1; i < n; i++) {
            float x0 = s.mx[i - 1];
            float y0 = s.my[i - 1];
            float x1 = s.mx[i];
            float y1 = s.my[i];
            float seg = (float) Math.hypot(x1 - x0, y1 - y0);
            float ex = x1;
            float ey = y1;
            if (acc + seg >= limit) {
                float t = seg <= 0.001f ? 0f : (limit - acc) / seg;
                ex = x0 + (x1 - x0) * t;
                ey = y0 + (y1 - y0) * t;
            }
            float dx = ex - x0;
            float dy = ey - y0;
            float len = (float) Math.hypot(dx, dy);
            if (len > 0.001f) {
                float nx = -dy / len * r;
                float ny = dx / len * r;
                p.moveTo(x0 + nx, y0 + ny);
                p.lineTo(ex + nx, ey + ny);
                p.lineTo(ex - nx, ey - ny);
                p.lineTo(x0 - nx, y0 - ny);
                p.close();
                p.addCircle(ex, ey, r, Path.Direction.CW);
            }
            acc += seg;
            if (acc >= limit) {
                break;
            }
        }
        return p;
    }

    private void drawPenHead(Canvas canvas, int ci, StrokeOrder.Stroke s, float progress) {
        float k = cell / 256f;
        float[] tip = tipPoint(s, progress);
        guideDot.setColor(0xE62E2A25);
        canvas.drawCircle(cellX[ci] + tip[0] * k, cellY[ci] + tip[1] * k, cell * 0.035f, guideDot);
    }

    private float[] tipPoint(StrokeOrder.Stroke s, float progress) {
        int n = s.points();
        if (n == 0) {
            return new float[]{0, 0};
        }
        float total = 0f;
        for (int i = 1; i < n; i++) {
            total += (float) Math.hypot(s.mx[i] - s.mx[i - 1], s.my[i] - s.my[i - 1]);
        }
        float limit = total * Math.min(1f, Math.max(0f, progress));
        float acc = 0f;
        for (int i = 1; i < n; i++) {
            float seg = (float) Math.hypot(s.mx[i] - s.mx[i - 1], s.my[i] - s.my[i - 1]);
            if (acc + seg >= limit && seg > 0.001f) {
                float t = (limit - acc) / seg;
                return new float[]{s.mx[i - 1] + (s.mx[i] - s.mx[i - 1]) * t,
                        s.my[i - 1] + (s.my[i] - s.my[i - 1]) * t};
            }
            acc += seg;
        }
        return new float[]{s.mx[n - 1], s.my[n - 1]};
    }

    private void drawStrokeNumber(Canvas canvas, int ci, int num, StrokeOrder.Stroke s) {
        float k = cell / 256f;
        float x = cellX[ci] + s.mx[0] * k;
        float y = cellY[ci] + s.my[0] * k;
        float r = cell * 0.048f;
        guideDot.setColor(0xCC8C6239);
        canvas.drawCircle(x, y, r, guideDot);
        guideText.setTextSize(cell * 0.058f);
        guideText.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = guideText.getFontMetrics();
        canvas.drawText(String.valueOf(num), x, y - (fm.ascent + fm.descent) / 2f, guideText);
    }

    /** 判一笔：把用户笔迹归一化到格子坐标后，与参考中线比对 */
    private void judgeStroke(Stroke s) {
        if (guideMode != GUIDE_JUDGE || guideStrokes == null
                || guideIndex >= guideStrokes.length || s.pts.size() < 2) {
            return;
        }
        int ci = cellAt(s.pts.get(0)[0], s.pts.get(0)[1]);
        float ox = cellX[ci];
        float oy = cellY[ci];
        float k = 256f / cell;

        int n = s.pts.size();
        float[] ux = new float[n];
        float[] uy = new float[n];
        for (int i = 0; i < n; i++) {
            ux[i] = (s.pts.get(i)[0] - ox) * k;
            uy[i] = (s.pts.get(i)[1] - oy) * k;
        }

        int expected = guideIndex;
        float dExp = compareStroke(ux, uy, guideStrokes[expected]);

        int bestIdx = -1;
        float bestOther = Float.MAX_VALUE;
        for (int i = 0; i < guideStrokes.length; i++) {
            if (i == expected) {
                continue;
            }
            float d = compareStroke(ux, uy, guideStrokes[i]);
            if (d < bestOther) {
                bestOther = d;
                bestIdx = i;
            }
        }

        boolean ok = dExp <= OK_DIST;
        judgeTotal++;
        if (ok) {
            judgeOk++;
            guideIndex++;
        }

        if (listener != null) {
            listener.onJudgeResult(ok, expected, ok ? expected : bestIdx, dExp, judgeOk, judgeTotal);
        }
        invalidate();
    }

    private int cellAt(float x, float y) {
        int cells = Math.max(1, Math.min(96, cellRows * cellCols));
        for (int i = 0; i < cells; i++) {
            if (x >= cellX[i] - dp(12) && x <= cellX[i] + cell + dp(12)
                    && y >= cellY[i] - dp(12) && y <= cellY[i] + cell + dp(12)) {
                return i;
            }
        }
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < cells; i++) {
            float cx = cellX[i] + cell / 2f;
            float cy = cellY[i] + cell / 2f;
            float d = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 用户笔迹与参考中线的平均偏差；正反两个方向取较小值 */
    private static float compareStroke(float[] ux, float[] uy, StrokeOrder.Stroke ref) {
        int m = ref.points();
        if (m < 2 || ux.length < 2) {
            return Float.MAX_VALUE;
        }
        float[] rxs = new float[m];
        float[] rys = new float[m];
        for (int i = 0; i < m; i++) {
            rxs[i] = ref.mx[i];
            rys[i] = ref.my[i];
        }

        final int N = 16;
        float[] a = resample(ux, uy, N);
        float[] b = resample(rxs, rys, N);

        float forward = meanDistance(a, b, N);

        float[] rev = new float[N * 2];
        for (int i = 0; i < N; i++) {
            rev[i * 2] = b[(N - 1 - i) * 2];
            rev[i * 2 + 1] = b[(N - 1 - i) * 2 + 1];
        }
        float backward = meanDistance(a, rev, N);
        return Math.min(forward, backward);
    }

    private static float[] resample(float[] xs, float[] ys, int n) {
        int cnt = xs.length;
        float[] out = new float[n * 2];
        if (cnt == 0) {
            return out;
        }
        float[] cum = new float[cnt];
        for (int i = 1; i < cnt; i++) {
            cum[i] = cum[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        float total = cum[cnt - 1];
        if (total <= 0.001f || cnt == 1) {
            for (int i = 0; i < n; i++) {
                out[i * 2] = xs[0];
                out[i * 2 + 1] = ys[0];
            }
            return out;
        }
        int j = 0;
        for (int i = 0; i < n; i++) {
            float target = total * i / (n - 1f);
            while (j < cnt - 2 && cum[j + 1] < target) {
                j++;
            }
            float segLen = cum[j + 1] - cum[j];
            float t = segLen <= 0f ? 0f : (target - cum[j]) / segLen;
            t = Math.max(0f, Math.min(1f, t));
            out[i * 2] = xs[j] + (xs[j + 1] - xs[j]) * t;
            out[i * 2 + 1] = ys[j] + (ys[j + 1] - ys[j]) * t;
        }
        return out;
    }

    private static float meanDistance(float[] a, float[] b, int n) {
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            sum += (float) Math.hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1]);
        }
        return sum / n;
    }

    // ---------------------------------------------------------------- 对外 API

    public void setListener(Listener l) {
        listener = l;
    }

    public void setPenWidth(float px) {
        penWidth = Math.max(1f, px);
    }

    public float getPenWidth() {
        return penWidth;
    }

    public void setEraser(boolean on) {
        eraser = on;
    }

    public boolean isEraser() {
        return eraser;
    }

    public void setPenOnly(boolean on) {
        penOnly = on;
    }

    public boolean isPenOnly() {
        return penOnly;
    }

    public void setRefChar(String ch) {
        refChar = ch;
        invalidate();
    }

    public String getRefChar() {
        return refChar;
    }

    public void setRefMode(int mode) {
        refMode = mode;
        invalidate();
    }

    public int getRefMode() {
        return refMode;
    }

    public void setGridMode(int mode) {
        gridMode = mode;
        invalidate();
    }

    public int getGridMode() {
        return gridMode;
    }

    /** 当前格子边长（px），供界面显示真实尺寸 */
    public float getCellSizePx() {
        return cell;
    }

    public int getCellColumns() {
        return cellCols;
    }

    public int getCellRows() {
        return cellRows;
    }

    /** 0.35 ~ 1.0，1.0 表示铺满可用区域 */
    public void setSizeScale(float scale) {
        float s = clamp(scale, 0.15f, 1f);
        if (Math.abs(s - sizeScale) < 0.001f) {
            return;
        }
        float oldSide = cell;
        sizeScale = s;
        layoutGrid(getWidth(), getHeight());

        // 格子是居中的，所以按「新旧边长比」绕画布中心缩放，笔迹就能跟着格子一起变大变小
        float k = oldSide > 1f ? cell / oldSide : 1f;
        if (Math.abs(k - 1f) > 0.002f) {
            scaleInk(k);
        }
        invalidate();
    }

    /** 把已有笔迹按比例绕画布中心缩放（坐标与线宽一起缩放，等效于整张纸缩放） */
    private void scaleInk(float k) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        for (Stroke st : strokes) {
            scaleStroke(st, cx, cy, k);
        }
        if (current != null) {
            scaleStroke(current, cx, cy, k);
        }
        rebuildInk();
    }

    private void scaleStroke(Stroke st, float cx, float cy, float k) {
        for (float[] p : st.pts) {
            p[0] = cx + (p[0] - cx) * k;
            p[1] = cy + (p[1] - cy) * k;
            p[2] = Math.max(0.6f, p[2] * k);
        }
    }

    public float getSizeScale() {
        return sizeScale;
    }

    public void undo() {
        if (strokes.isEmpty()) return;
        redoStack.add(strokes.remove(strokes.size() - 1));
        rebuildInk();
        notifyHistory();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        strokes.add(redoStack.remove(redoStack.size() - 1));
        rebuildInk();
        notifyHistory();
    }

    public boolean canUndo() {
        return !strokes.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clearAll() {
        strokes.clear();
        redoStack.clear();
        current = null;
        guideIndex = 0;
        judgeOk = 0;
        judgeTotal = 0;
        rebuildInk();
        notifyHistory();
    }

    /** 导出整张练习纸（底色 + 格子 + 参照字 + 笔迹） */
    public Bitmap exportBitmap() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawRect(0, 0, w, h, bgPaint);
        drawGrid(c);
        drawRefChar(c);
        if (ink != null && !ink.isRecycled()) {
            c.drawBitmap(ink, 0, 0, null);
        }
        return out;
    }

    /** 当前笔迹条数，用于界面提示 */
    public int strokeCount() {
        return strokes.size();
    }
}
