package com.qytong.rfprog;

import java.util.ArrayList;
import java.util.List;

/**
 * 亚音 (CTCSS/DCS) 编解码 —— 与 PC 版 GT-12.exe 完全一致的编码
 *
 * 存储 (2 字节):
 *   OFF  : FF FF
 *   CTCSS: byte0 = BCD(0.1Hz单位 % 100), byte1 = BCD(0.1Hz单位 / 100)
 *          例 97.4Hz -> 974 -> 74 09
 *   DCS  : byte0 = BCD(后两位), byte1 = 0x80 | BCD(百位) | (0x40 反相)
 *          例 D023N -> 23 80 ; D431I -> 31 C4
 */
public class Tone {

    public static final int OFF = 0;
    public static final int CTCSS = 1;
    public static final int DCS = 2;

    public int type = OFF;
    public double hz = 0;          // CTCSS 频率
    public int dcsCode = 0;        // DCS 八进制代码 (23..754)
    public boolean inverted = false;

    /** 49 个 CTCSS 音调 (Hz) —— 提取自 GT-12.exe 表 @0x62e20 */
    public static final double[] CTCSS_TONES = {
            67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5,
            94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
            131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
            171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
            203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3
    };

    /** 104 个 DCS 代码 (八进制) —— 提取自 GT-12.exe 表 @0x62e88 */
    public static final int[] DCS_CODES = {
            23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74,
            114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156, 162,
            165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252,
            255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325, 331, 332, 343,
            346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432, 445, 446,
            452, 454, 455, 462, 464, 465, 466, 503, 506, 516, 523, 526, 532, 546,
            565, 606, 612, 624, 627, 631, 632, 645, 654, 662, 664, 703, 712, 723,
            731, 732, 734, 743
    };

    public static Tone off() {
        return new Tone();
    }

    public static Tone ctcss(double hz) {
        Tone t = new Tone();
        t.type = CTCSS;
        t.hz = hz;
        return t;
    }

    public static Tone dcs(int code, boolean inverted) {
        Tone t = new Tone();
        t.type = DCS;
        t.dcsCode = code;
        t.inverted = inverted;
        return t;
    }

    /** 从 2 字节解码 */
    public static Tone decode(byte[] rec, int off) {
        int b0 = rec[off] & 0xFF;
        int b1 = rec[off + 1] & 0xFF;
        if (b1 == 0xFF && b0 == 0xFF) return off();
        if ((b1 & 0x80) != 0) {
            int code = bcdVal(b0) + (b1 & 0x0F) * 100;
            return dcs(code, (b1 & 0x40) != 0);
        }
        int v = bcdVal(b1) * 100 + bcdVal(b0);
        Tone t = ctcss(v / 10.0);
        return t;
    }

    /** 编码为 2 字节 */
    public byte[] encode() {
        byte[] out = new byte[2];
        if (type == OFF) {
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xFF;
            return out;
        }
        if (type == CTCSS) {
            int v = (int) Math.round(hz * 10);
            out[0] = bcd(v % 100);
            out[1] = bcd(v / 100);
            return out;
        }
        // DCS: 八进制码转十进制各位
        int code = dcsCode;
        int d1 = code / 100, d2 = (code / 10) % 10, d3 = code % 10;
        out[0] = (byte) ((d2 << 4) | d3);
        out[1] = (byte) (0x80 | d1 | (inverted ? 0x40 : 0));
        return out;
    }

    public String label() {
        if (type == OFF) return "OFF";
        if (type == CTCSS) return String.format("%.1f Hz", hz);
        return "D" + String.format("%03d", dcsCode) + (inverted ? "I" : "");
    }

    private static int bcdVal(int b) {
        return ((b >> 4) & 0xF) * 10 + (b & 0xF);
    }

    private static byte bcd(int v) {
        return (byte) (((v / 10) << 4) | (v % 10));
    }

    /** 所有可选条目（用于 Spinner） */
    public static List<Tone> allOptions() {
        List<Tone> list = new ArrayList<Tone>();
        list.add(off());
        for (double hz : CTCSS_TONES) list.add(ctcss(hz));
        for (int code : DCS_CODES) {
            list.add(dcs(code, false));
            list.add(dcs(code, true));
        }
        return list;
    }

    public boolean sameAs(Tone other) {
        if (other == null) return false;
        if (type != other.type) return false;
        if (type == CTCSS) return Math.abs(hz - other.hz) < 0.06;
        if (type == DCS) return dcsCode == other.dcsCode && inverted == other.inverted;
        return true;
    }
}
