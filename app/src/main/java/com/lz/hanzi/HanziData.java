package com.lz.hanzi;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 《通用规范汉字表》一级（3500 字）、二级（3000 字）字库。
 *
 * 资源文件 assets/hanzi_l1.tsv、hanzi_l2.tsv，每行：字 \t 拼音 \t 无声调拼音 \t 笔画数。
 * 顺序沿用官方字表的笔画排序，练起来由简到繁。
 */
public final class HanziData {

    public static final class Entry {
        public final String ch;
        public final String pinyin;    // yǒng
        public final String toneless;  // yong
        public final int strokes;
        public final int level;        // 1 或 2

        Entry(String ch, String pinyin, String toneless, int strokes, int level) {
            this.ch = ch;
            this.pinyin = pinyin;
            this.toneless = toneless;
            this.strokes = strokes;
            this.level = level;
        }
    }

    private static List<Entry> all;
    private static List<Entry> level1;
    private static List<Entry> level2;

    private HanziData() {
    }

    public static synchronized void load(Context context) {
        if (all != null) {
            return;
        }
        Context app = context.getApplicationContext();
        ArrayList<Entry> l1 = new ArrayList<>(3500);
        ArrayList<Entry> l2 = new ArrayList<>(3000);
        readAsset(app, "hanzi_l1.tsv", 1, l1);
        readAsset(app, "hanzi_l2.tsv", 2, l2);

        ArrayList<Entry> merged = new ArrayList<>(l1.size() + l2.size());
        merged.addAll(l1);
        merged.addAll(l2);

        level1 = Collections.unmodifiableList(l1);
        level2 = Collections.unmodifiableList(l2);
        all = Collections.unmodifiableList(merged);
    }

    private static void readAsset(Context context, String name, int level, List<Entry> out) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length < 4) {
                    continue;
                }
                int strokes = 0;
                try {
                    strokes = Integer.parseInt(f[3].trim());
                } catch (NumberFormatException ignored) {
                }
                out.add(new Entry(f[0], f[1], f[2], strokes, level));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<Entry> all() {
        return all;
    }

    public static List<Entry> byLevel(int level) {
        return level == 2 ? level2 : level1;
    }

    public static int indexOf(String ch) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).ch.equals(ch)) {
                return i;
            }
        }
        return -1;
    }
}
