package com.lz.hanzi;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 笔顺数据：每字按笔顺存「字形轮廓路径」+「中线」+「平均半宽」。
 *
 * 资源 assets/strokes2.bin 由 hanzi-writer-data（Make Me a Hanzi / Arphic 公开授权）转换而来：
 * 轮廓只用 M/L/Q/C/Z 绝对命令，坐标归一化到 0..255，y 轴向下；
 * 参照字和笔顺提示都由同一份数据绘制，因此严格同源对齐。
 *
 * 覆盖：6371/6500（一级 3500 全覆盖，二级缺 129 个生僻字，缺失时退回字体渲染）。
 */
public final class StrokeOrder {

    public static final class Stroke {
        /** 路径命令类型：0=M 1=L 2=Q 3=C 4=Z */
        public final int[] types;
        /** 命令参数展平存放（M/L 2 个，Q 4 个，C 6 个，Z 0 个） */
        public final int[] coords;
        /** 中线点，x/y 交替 */
        public final int[] mx;
        public final int[] my;
        /** 平均半宽（0..255 归一化） */
        public final int radius;

        Stroke(int[] types, int[] coords, int[] mx, int[] my, int radius) {
            this.types = types;
            this.coords = coords;
            this.mx = mx;
            this.my = my;
            this.radius = radius;
        }

        public int points() {
            return mx.length;
        }
    }

    private static final Map<Integer, Integer> INDEX = new HashMap<>(7000);
    private static byte[] DATA;
    private static boolean loaded;

    private StrokeOrder() {
    }

    public static synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = context.getAssets().open("strokes2.bin")) {
            DATA = readAll(in);
        } catch (IOException e) {
            e.printStackTrace();
            DATA = new byte[0];
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(DATA);
        if (DATA.length < 8) {
            return;
        }
        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'H' || magic[1] != 'Z' || magic[2] != 'S' || magic[3] != '2') {
            return;
        }
        int count = buf.getInt();
        for (int i = 0; i < count; i++) {
            int cp = buf.getInt();
            INDEX.put(cp, buf.position());
            int strokeCount = buf.get() & 0xFF;
            for (int s = 0; s < strokeCount; s++) {
                int cmdCount = buf.get() & 0xFF;
                for (int c = 0; c < cmdCount; c++) {
                    int type = buf.get() & 0xFF;
                    buf.position(buf.position() + coordCount(type));
                }
                int medCount = buf.get() & 0xFF;
                buf.position(buf.position() + medCount * 2 + 1);
            }
        }
    }

    private static int coordCount(int type) {
        switch (type) {
            case 2:
                return 4;
            case 3:
                return 6;
            case 0:
            case 1:
                return 2;
            default:
                return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 22);
        byte[] tmp = new byte[16384];
        int n;
        while ((n = in.read(tmp)) > 0) {
            out.write(tmp, 0, n);
        }
        return out.toByteArray();
    }

    /** 该字没有笔顺数据时返回 null */
    public static Stroke[] get(String ch) {
        if (DATA == null || ch == null || ch.isEmpty()) {
            return null;
        }
        Integer off = INDEX.get(ch.codePointAt(0));
        if (off == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(DATA);
        buf.position(off);
        int strokeCount = buf.get() & 0xFF;
        Stroke[] out = new Stroke[strokeCount];
        for (int s = 0; s < strokeCount; s++) {
            int cmdCount = buf.get() & 0xFF;
            int[] types = new int[cmdCount];
            int[] coords = new int[cmdCount * 6];
            int ci = 0;
            for (int c = 0; c < cmdCount; c++) {
                int type = buf.get() & 0xFF;
                types[c] = type;
                int k = coordCount(type);
                for (int j = 0; j < k; j++) {
                    coords[ci++] = buf.get() & 0xFF;
                }
            }
            int medCount = buf.get() & 0xFF;
            int[] mx = new int[medCount];
            int[] my = new int[medCount];
            for (int p = 0; p < medCount; p++) {
                mx[p] = buf.get() & 0xFF;
                my[p] = buf.get() & 0xFF;
            }
            int radius = buf.get() & 0xFF;
            out[s] = new Stroke(types, coords, mx, my, radius > 0 ? radius : 18);
        }
        return out;
    }

    public static boolean has(String ch) {
        return ch != null && INDEX.containsKey(ch.codePointAt(0));
    }
}
