package com.lz.hanzi;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 墨格练字 —— 单页练字界面。
 *
 * 字库为《通用规范汉字表》一级 3500 字 + 二级 3000 字，选字走独立的选择页
 * （拼音 / 汉字搜索、按字表分级浏览）。
 */
public class MainActivity extends Activity implements PracticeView.Listener {

    private static final int REQ_PICK = 1001;

    private static final int SIZE_MIN = 15;
    private static final int SIZE_MAX = 100;

    private static final float[] WIDTHS = {6f, 10f, 15f, 20f, 26f};

    private static final String[] REF_LABELS = {"描红", "示范", "隐藏"};
    private static final String[] GRID_LABELS = {"田字格", "米字格"};

    private static final String[] GUIDE_LABELS = {"关", "提示", "判定"};

    private PracticeView pad;
    private TextView status;
    private Button charBtn, penBtn, widthBtn, refBtn, gridBtn, sizeBtn, guideBtn, playBtn, penOnlyBtn, undoBtn, redoBtn;

    private int charIdx;
    private int widthIdx = 2;
    private int gridCols = 1;
    private int gridRows = 1;
    private int gridPct = 100;
    private int guideMode = PracticeView.GUIDE_OFF;
    private int refMode = PracticeView.REF_GUIDE;
    private int gridMode = PracticeView.GRID_TIAN;
    private boolean penOnly = false;

    private SharedPreferences sp;
    private PopupWindow widthPopup;
    private PopupWindow optionPopup;
    private PopupWindow sizePopup;
    private float lastPressure, lastWidth;
    private int lastTool = -1;
    private long lastStatusAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        HanziData.load(this);
        List<HanziData.Entry> all = HanziData.all();

        sp = getSharedPreferences("moge", MODE_PRIVATE);
        int savedIdx = sp.getInt("charIdx", HanziData.indexOf("永"));
        charIdx = Math.max(0, Math.min(all.size() - 1, savedIdx));
        widthIdx = sp.getInt("widthIdx", widthIdx);

        gridPct = sp.getInt("gridPct", gridPct);
        guideMode = sp.getInt("guideMode", guideMode);
        refMode = sp.getInt("refMode", refMode);
        gridMode = sp.getInt("gridMode", gridMode);
        penOnly = sp.getBoolean("penOnly", penOnly);

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        setImmersive();
        setContentView(buildUi());

        pad.setRefChar(all.get(charIdx).ch);
        pad.setPenWidth(dp(WIDTHS[widthIdx]));
        pad.setRefMode(refMode);
        pad.setGridMode(gridMode);

        pad.setSizeScale(gridPct / 100f);
        pad.setGuideMode(guideMode);
        pad.setPenOnly(penOnly);
        pad.setListener(this);
        applyGuideData();
        syncButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sp.edit()
                .putInt("charIdx", charIdx)
                .putInt("widthIdx", widthIdx)

                .putInt("gridPct", gridPct)
                .putInt("guideMode", guideMode)
                .putInt("refMode", refMode)
                .putInt("gridMode", gridMode)
                .putBoolean("penOnly", penOnly)
                .apply();
    }

    // ---------------------------------------------------------------- 界面搭建

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F4EE);

        HorizontalScrollView bar = new HorizontalScrollView(this);
        bar.setHorizontalScrollBarEnabled(false);
        bar.setFillViewport(false);
        bar.setBackgroundColor(0xFFEFE9DF);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(6);
        tools.setPadding(p, 0, p, 0);

        charBtn = mkButton("永", v -> openPicker());
        charBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        charBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        charBtn.setCompoundDrawablePadding(dp(6));
        charBtn.setPadding(dp(14), 0, dp(10), 0);

        penBtn = mkButton("笔", v -> {
            pad.setEraser(!pad.isEraser());
            syncButtons();
        });
        widthBtn = mkButton("粗细", v -> toggleWidthPopup(v));
        widthBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        widthBtn.setCompoundDrawablePadding(dp(6));
        widthBtn.setPadding(dp(12), 0, dp(9), 0);
        refBtn = mkButton("示范", v -> showOptions(v, REF_LABELS, refMode, idx -> {
            refMode = idx;
            pad.setRefMode(idx);
            syncButtons();
        }));
        refBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        refBtn.setCompoundDrawablePadding(dp(6));
        refBtn.setPadding(dp(12), 0, dp(9), 0);

        gridBtn = mkButton("田字格", v -> showOptions(v, GRID_LABELS, gridMode, idx -> {
            gridMode = idx;
            pad.setGridMode(idx);
            syncButtons();
        }));
        gridBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        gridBtn.setCompoundDrawablePadding(dp(6));
        gridBtn.setPadding(dp(12), 0, dp(9), 0);



        sizeBtn = mkButton("格大小 100%", v -> showSizePopup(v));
        sizeBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        sizeBtn.setCompoundDrawablePadding(dp(6));
        sizeBtn.setPadding(dp(12), 0, dp(9), 0);

        guideBtn = mkButton("笔顺:关", v -> showOptions(v, GUIDE_LABELS, guideMode, idx -> {
            guideMode = idx;
            pad.setGuideMode(idx);
            if (idx != PracticeView.GUIDE_OFF && !pad.hasGuide()) {
                toast(HanziData.all().get(charIdx).ch + " 暂无笔顺数据");
            }
            syncButtons();
        }));
        guideBtn.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        guideBtn.setCompoundDrawablePadding(dp(6));
        guideBtn.setPadding(dp(12), 0, dp(9), 0);

        playBtn = mkButton("播放", v -> {
            if (pad.hasGuide()) {
                pad.playGuideAnimation();
            } else {
                toast("该字暂无笔顺数据");
            }
        });
        penOnlyBtn = mkButton("仅笔:关", v -> {
            penOnly = !penOnly;
            pad.setPenOnly(penOnly);
            syncButtons();
        });

        undoBtn = mkButton("撤销", v -> pad.undo());
        redoBtn = mkButton("重做", v -> pad.redo());
        Button clear = mkButton("清空", v -> pad.clearAll());
        Button save = mkButton("保存", v -> savePng());

        tools.addView(charBtn);
        tools.addView(divider());
        tools.addView(penBtn);
        tools.addView(widthBtn);
        tools.addView(divider());
        tools.addView(refBtn);
        tools.addView(gridBtn);

        tools.addView(sizeBtn);
        tools.addView(guideBtn);
        tools.addView(playBtn);
        tools.addView(penOnlyBtn);
        tools.addView(divider());
        tools.addView(undoBtn);
        tools.addView(redoBtn);
        tools.addView(clear);
        tools.addView(save);

        bar.addView(tools, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(54)));

        FrameLayout area = new FrameLayout(this);
        pad = new PracticeView(this);
        area.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        status.setTextColor(0xFF8A8378);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xFFEFE9DF);
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        root.addView(area, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        // 沉浸式：把状态栏高度让出来
        root.setPadding(0, statusBarHeight(), 0, 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });
        return root;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0xFFD9D2C5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(22));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        d.setLayoutParams(lp);
        return d;
    }

    private Button mkButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(0xFF2E2A25);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(bgDrawable(0xFFFFFFFF));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable bgDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0xFFD8D0C2);
        return d;
    }

    private void syncButtons() {
        HanziData.Entry e = HanziData.all().get(charIdx);
        charBtn.setText(e.ch);

        penBtn.setText(pad.isEraser() ? "橡皮" : "笔");
        penBtn.setBackground(bgDrawable(pad.isEraser() ? 0xFFFFE7C2 : 0xFFFFFFFF));

        widthBtn.setText("粗细 " + (int) WIDTHS[widthIdx]);
        refBtn.setText(REF_LABELS[refMode]);
        gridBtn.setText(GRID_LABELS[gridMode]);

        sizeBtn.setText(cellSizeText());
        guideBtn.setText("笔顺:" + GUIDE_LABELS[guideMode]);
        guideBtn.setBackground(bgDrawable(guideMode == PracticeView.GUIDE_OFF ? 0xFFFFFFFF : 0xFFFFE7C2));
        playBtn.setEnabled(pad.hasGuide());
        penOnlyBtn.setText(penOnly ? "仅笔:开" : "仅笔:关");
        penOnlyBtn.setBackground(bgDrawable(penOnly ? 0xFFFFE7C2 : 0xFFFFFFFF));

        undoBtn.setEnabled(pad.canUndo());
        redoBtn.setEnabled(pad.canRedo());
        updateStatus(lastPressure, lastWidth, lastTool);
    }

    private void updateStatus(float pressure, float width, int tool) {
        HanziData.Entry e = HanziData.all().get(charIdx);
        String toolName = tool == android.view.MotionEvent.TOOL_TYPE_STYLUS ? "手写笔"
                : tool == android.view.MotionEvent.TOOL_TYPE_FINGER ? "手指" : "—";
        String p = pressure > 0 ? String.format(Locale.CHINA, "笔压 %.2f", pressure) : "笔压 —";
        String w = width > 0 ? String.format(Locale.CHINA, "线宽 %.1f", width) : "线宽 —";
        String head = e.pinyin.isEmpty() ? e.ch : (e.ch + " " + e.pinyin);

        String guide = "";
        if (pad.getGuideMode() != PracticeView.GUIDE_OFF && pad.hasGuide()) {
            if (pad.getGuideMode() == PracticeView.GUIDE_JUDGE) {
                guide = String.format(Locale.CHINA, " · 笔顺 %d/%d · 正确 %d/%d",
                        Math.min(pad.guideIndex(), pad.guideTotal()), pad.guideTotal(),
                        pad.judgeOk(), pad.judgeTotal());
            } else {
                guide = String.format(Locale.CHINA, " · 第 %d/%d 笔",
                        Math.min(pad.guideIndex() + 1, pad.guideTotal()), pad.guideTotal());
            }
        }

        int cells = pad.getCellColumns() * pad.getCellRows();
        status.setText(String.format(Locale.CHINA, "%s · %s · %s · %s · %d 格 · 笔迹 %d%s",
                head, p, w, toolName, cells, pad.strokeCount(), guide));
    }

    /** 通用下拉：参照字 / 格型 / 格数 */
    private void showOptions(View anchor, String[] options, int selected,
                             java.util.function.IntConsumer onPick) {
        if (optionPopup != null && optionPopup.isShowing()) {
            optionPopup.dismiss();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(bgDrawable(0xFFFFFFFF));

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean isSelected = i == selected;

            TextView tv = new TextView(this);
            tv.setText(options[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tv.setTextColor(isSelected ? 0xFF8C6239 : 0xFF2E2A25);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(16), dp(11), dp(16), dp(11));

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(9));
            rowBg.setColor(isSelected ? 0x1F8C6239 : 0x00000000);
            tv.setBackground(rowBg);

            tv.setOnClickListener(v -> {
                onPick.accept(idx);
                if (optionPopup != null) {
                    optionPopup.dismiss();
                }
            });
            list.addView(tv);
        }

        optionPopup = new PopupWindow(list, dp(152), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        optionPopup.setBackgroundDrawable(bgDrawable(0xFFFFFFFF));
        optionPopup.setOutsideTouchable(true);
        optionPopup.setElevation(dp(12));
        optionPopup.showAsDropDown(anchor, -dp(6), dp(6));
    }

    /** 粗细下拉：每个选项用圆点画出该粗细，点一下即切换 */
    private void toggleWidthPopup(View anchor) {
        if (widthPopup != null && widthPopup.isShowing()) {
            widthPopup.dismiss();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(bgDrawable(0xFFFFFFFF));

        for (int i = 0; i < WIDTHS.length; i++) {
            final int idx = i;
            boolean selected = i == widthIdx;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(9));
            rowBg.setColor(selected ? 0x1F8C6239 : 0x00000000);
            row.setBackground(rowBg);

            View dot = new View(this);
            int d = Math.max(dp(4), dp(WIDTHS[i]));
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(0xFF2E2A25);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(d, d);
            dlp.rightMargin = dp(12);
            dlp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(dot, dlp);

            TextView label = new TextView(this);
            label.setText(String.valueOf((int) WIDTHS[i]));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            label.setTextColor(selected ? 0xFF8C6239 : 0xFF2E2A25);
            row.addView(label);

            row.setOnClickListener(v -> selectWidth(idx));
            list.addView(row);
        }

        widthPopup = new PopupWindow(list, dp(148), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        widthPopup.setBackgroundDrawable(bgDrawable(0xFFFFFFFF));
        widthPopup.setOutsideTouchable(true);
        widthPopup.setElevation(dp(12));
        widthPopup.showAsDropDown(anchor, -dp(6), dp(6));
    }

    private void selectWidth(int idx) {
        widthIdx = idx;
        pad.setPenWidth(dp(WIDTHS[idx]));
        syncButtons();
        if (widthPopup != null) {
            widthPopup.dismiss();
        }
    }

    /** 格子大小滑块：拖动时实时改变格子尺寸 */
    private void showSizePopup(View anchor) {
        if (sizePopup != null && sizePopup.isShowing()) {
            sizePopup.dismiss();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(8));
        box.setBackground(bgDrawable(0xFFFFFFFF));

        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(0xFF2E2A25);
        label.setText("格子大小 " + gridPct + "%");

        SeekBar bar = new SeekBar(this);
        bar.setMax(SIZE_MAX - SIZE_MIN);
        bar.setProgress(Math.max(0, Math.min(SIZE_MAX - SIZE_MIN, gridPct - SIZE_MIN)));
        bar.setPadding(dp(8), dp(18), dp(8), dp(18));
        ColorStateList tint = ColorStateList.valueOf(0xFF8C6239);
        bar.setProgressTintList(tint);
        bar.setThumbTintList(tint);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFD8D0C2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = SIZE_MIN + progress;
                gridPct = pct;
                pad.setSizeScale(pct / 100f);
                int cells = pad.getCellColumns() * pad.getCellRows();
                label.setText(cellSizeText() + " · " + cells + " 格");
                sizeBtn.setText(cellSizeText());
                updateStatus(lastPressure, lastWidth, lastTool);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        box.addView(label);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        sizePopup = new PopupWindow(box, dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sizePopup.setBackgroundDrawable(bgDrawable(0xFFFFFFFF));
        sizePopup.setOutsideTouchable(true);
        sizePopup.setElevation(dp(12));
        sizePopup.showAsDropDown(anchor, -dp(6), dp(6));
    }

    private void applyChar() {
        pad.setRefChar(HanziData.all().get(charIdx).ch);
        pad.clearAll();
        applyGuideData();
        syncButtons();
    }

    /** 载入当前字的笔顺数据；没有数据时给个提示 */
    private void applyGuideData() {
        String ch = HanziData.all().get(charIdx).ch;
        StrokeOrder.load(this);
        StrokeOrder.Stroke[] strokes = StrokeOrder.get(ch);
        pad.setGuideStrokes(strokes);
        if (strokes == null && guideMode != PracticeView.GUIDE_OFF) {
            toast(ch + " 暂无笔顺数据");
        }
    }

    private void openPicker() {
        Intent i = new Intent(this, CharPickerActivity.class);
        i.putExtra(CharPickerActivity.EXTRA_INDEX, charIdx);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            int idx = data.getIntExtra(CharPickerActivity.RESULT_INDEX, -1);
            if (idx >= 0 && idx < HanziData.all().size() && idx != charIdx) {
                charIdx = idx;
                applyChar();
            }
        }
    }

    // ---------------------------------------------------------------- Listener

    @Override
    public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        undoBtn.setEnabled(canUndo);
        redoBtn.setEnabled(canRedo);
        updateStatus(lastPressure, lastWidth, lastTool);
    }

    /** 显示真实格子边长：行列自适应后百分比会失真，直接用 dp 更直观 */
    private String cellSizeText() {
        float px = pad == null ? 0f : pad.getCellSizePx();
        int dpv = Math.max(1, Math.round(px / getResources().getDisplayMetrics().density));
        return "格大小 " + dpv + "dp";
    }

    @Override
    public void onGridChanged(int cols, int rows) {
        gridCols = Math.max(1, cols);
        gridRows = Math.max(1, rows);
        if (status != null) {
            updateStatus(lastPressure, lastWidth, lastTool);
        }
    }

    @Override
    public void onJudgeResult(boolean ok, int expected, int matched, float distance, int done, int total) {
        if (!ok) {
            String msg;
            if (matched >= 0 && matched != expected) {
                msg = "顺序不对：这笔像第 " + (matched + 1) + " 笔，当前应写第 " + (expected + 1) + " 笔";
            } else {
                msg = "笔画对不上，当前应写第 " + (expected + 1) + " 笔";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } else if (pad.guideTotal() > 0 && pad.guideIndex() >= pad.guideTotal()) {
            Toast.makeText(this, "笔顺完成！正确 " + pad.judgeOk() + "/" + pad.judgeTotal() + " 笔",
                    Toast.LENGTH_LONG).show();
        }
        updateStatus(lastPressure, lastWidth, lastTool);
    }

    @Override
    public void onPenSample(float pressure, float width, int toolType) {
        lastPressure = pressure;
        lastWidth = width;
        lastTool = toolType;
        // 120Hz 采样下每秒上百次 setText 会拖慢书写，这里做节流
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStatusAt < 90) {
            return;
        }
        lastStatusAt = now;
        updateStatus(pressure, width, toolType);
    }

    // ---------------------------------------------------------------- 保存

    private void savePng() {
        Bitmap bmp = pad.exportBitmap();
        String name = "练字_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/墨格练字");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new IllegalStateException("无法创建相册条目");
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IllegalStateException("无法写入");
                    }
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                toast("已保存到相册：Pictures/墨格练字/" + name);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "墨格练字");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建目录");
                }
                File f = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                toast("已保存：" + f.getAbsolutePath());
            }
        } catch (Exception ex) {
            toast("保存失败：" + ex.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 沉浸式 & 工具

    private void setImmersive() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        View decor = w.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
