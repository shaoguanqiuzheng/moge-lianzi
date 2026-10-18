package com.lz.hanzi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 选字页：一级字表（3500 字）/ 二级字表（3000 字）切换 + 拼音/汉字搜索。
 *
 * 结果通过 RESULT_INDEX 返回该字在 HanziData.all() 里的下标。
 */
public class CharPickerActivity extends Activity {

    public static final String EXTRA_INDEX = "index";
    public static final String RESULT_INDEX = "index";

    private static final int LEVEL1 = 1;
    private static final int LEVEL2 = 2;

    private GridView grid;
    private TextView countLabel;
    private Button tab1, tab2;
    private EditText search;
    private GridAdapter adapter;

    private int level = LEVEL1;
    private int currentIndex = 0;
    private final List<HanziData.Entry> filtered = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HanziData.load(this);
        currentIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (currentIndex >= 0 && currentIndex < HanziData.all().size()) {
            level = HanziData.all().get(currentIndex).level;
        }
        setContentView(buildUi());
        applyFilter(true);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F4EE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFEFE9DF);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));

        Button close = new Button(this);
        close.setText("‹");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        close.setAllCaps(false);
        close.setTextColor(0xFF2E2A25);
        close.setBackground(bg(0xFFFFFFFF));
        close.setPadding(0, 0, 0, 0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(44), dp(40));
        clp.rightMargin = dp(8);
        bar.addView(close, clp);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("拼音或汉字，如 guo / 国 / yong");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        search.setTextColor(0xFF2E2A25);
        search.setHintTextColor(0xFFA39C91);
        search.setBackground(bg(0xFFFFFFFF));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(false);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        slp.rightMargin = dp(8);
        bar.addView(search, slp);

        tab1 = tabButton("一级 3500", () -> switchLevel(LEVEL1));
        tab2 = tabButton("二级 3000", () -> switchLevel(LEVEL2));
        bar.addView(tab1);
        bar.addView(tab2);

        countLabel = new TextView(this);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        countLabel.setTextColor(0xFF8A8378);
        countLabel.setPadding(dp(14), dp(5), dp(14), dp(5));
        countLabel.setIncludeFontPadding(false);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(58));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(2));
        grid.setHorizontalSpacing(0);
        grid.setVerticalScrollBarEnabled(true);
        grid.setPadding(dp(6), dp(4), dp(6), dp(6));
        grid.setClipToPadding(false);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) {
                return;
            }
            String ch = filtered.get(position).ch;
            int idx = HanziData.indexOf(ch);
            Intent data = new Intent();
            data.putExtra(RESULT_INDEX, idx);
            setResult(RESULT_OK, data);
            finish();
        });

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(countLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button tabButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(0xFF2E2A25);
        b.setBackground(bg(0xFFFFFFFF));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void switchLevel(int lvl) {
        level = lvl;
        applyFilter(true);
    }

    private void applyFilter(boolean scrollToCurrent) {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<HanziData.Entry> source = q.isEmpty() ? HanziData.byLevel(level) : HanziData.all();

        filtered.clear();
        for (HanziData.Entry e : source) {
            if (q.isEmpty()
                    || e.ch.equals(q)
                    || e.toneless.startsWith(q)
                    || e.toneless.contains(q)) {
                filtered.add(e);
            }
        }

        tab1.setBackground(bg(level == LEVEL1 ? 0xFFFFE7C2 : 0xFFFFFFFF));
        tab2.setBackground(bg(level == LEVEL2 ? 0xFFFFE7C2 : 0xFFFFFFFF));

        countLabel.setText(q.isEmpty()
                ? (level == LEVEL1 ? "一级字表（通用规范汉字表）· 3500 字，按笔画排序"
                                   : "二级字表（通用规范汉字表）· 3000 字，按笔画排序")
                : ("搜索「" + q + "」· " + filtered.size() + " 个结果"));

        adapter.notifyDataSetChanged();

        if (scrollToCurrent && q.isEmpty()) {
            int pos = 0;
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i) == HanziData.all().get(currentIndex)) {
                    pos = i;
                    break;
                }
            }
            final int target = pos;
            grid.post(() -> grid.setSelection(target));
        }
    }

    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(color);
        d.setStroke(dp(1), 0xFFD8D0C2);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filtered.size();
        }

        @Override
        public Object getItem(int position) {
            return filtered.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(CharPickerActivity.this);
            HanziData.Entry e = filtered.get(position);
            String py = e.pinyin.isEmpty() ? " " : e.pinyin;

            SpannableString ss = new SpannableString(e.ch + "\n" + py);
            int start = e.ch.length() + 1;
            ss.setSpan(new RelativeSizeSpan(0.40f), start, ss.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(0xFF9A938A), start, ss.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);

            tv.setText(ss);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
            tv.setGravity(Gravity.CENTER);
            tv.setLineSpacing(0f, 0.92f);
            tv.setPadding(0, dp(3), 0, dp(3));
            tv.setLayoutParams(new AbsListView.LayoutParams(dp(58), dp(60)));

            boolean selected = HanziData.all().get(currentIndex) == e;
            GradientDrawable bgd = new GradientDrawable();
            bgd.setCornerRadius(dp(10));
            bgd.setColor(selected ? 0x338C6239 : 0x00000000);
            tv.setBackground(bgd);
            tv.setTextColor(selected ? 0xFF8C6239 : 0xFF2E2A25);
            return tv;
        }
    }
}
