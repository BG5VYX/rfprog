package com.qytong.rfprog;

/**
 * 十六进制 / BCD 频率编解码工具
 */
public class HexUtil {

    public static String bytesToHex(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    public static String bytesToHex(byte[] data) {
        return bytesToHex(data, 0, data.length);
    }

    /** 32 字节分 2 行显示 */
    public static String blockToHexLines(byte[] data) {
        return bytesToHex(data, 0, 16) + "\n" + bytesToHex(data, 16, 16);
    }

    public static byte[] hexToBytes(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                sb.append(c);
            }
        }
        if (sb.length() % 2 != 0) throw new IllegalArgumentException("HEX 长度必须为偶数");
        byte[] out = new byte[sb.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(sb.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static int checksum(byte[] data, int off, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += data[off + i] & 0xFF;
        return s & 0xFF;
    }

    /**
     * KT-10R 频率编码：4 字节 BCD，共 8 个十进制数字，代表频率 × 10Hz。
     * 例：435.12500 MHz -> "43512500" -> 0x43 0x51 0x25 0x00
     */
    public static long bcdToFreqHz(byte[] data, int off) {
        long digits = 0;
        for (int i = 0; i < 4; i++) {
            int b = data[off + i] & 0xFF;
            int hi = (b >> 4) & 0xF, lo = b & 0xF;
            if (hi > 9 || lo > 9) return -1;
            digits = digits * 100 + hi * 10 + lo;
        }
        return digits * 10L;
    }

    /** 频率(Hz) -> 4 字节 BCD。返回 null 表示超出范围 */
    public static byte[] freqHzToBcd(long hz) {
        long digits = hz / 10L;   // 8 位十进制
        if (digits < 0 || digits > 99999999L) return null;
        byte[] out = new byte[4];
        for (int i = 3; i >= 0; i--) {
            int v = (int) (digits % 100);
            out[i] = (byte) (((v / 10) << 4) | (v % 10));
            digits /= 100;
        }
        return out;
    }

    /** BCD 4 字节是否是可识别的频率（25MHz - 600MHz） */
    public static boolean isPlausibleFreq(byte[] data, int off) {
        long hz = bcdToFreqHz(data, off);
        if (hz < 0) return false;
        double mhz = hz / 1e6;
        return mhz >= 25.0 && mhz <= 600.0;
    }

    /** 435.01234 (MHz, 5位小数) -> Hz */
    public static long parseMhz(String text) {
        String t = text.trim();
        if (t.isEmpty()) return -1;
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return -1;
        }
        return Math.round(v * 1000000.0);
    }

    public static String freqHzToMhzText(long hz) {
        double mhz = hz / 1000000.0;
        return String.format("%.5f", mhz);
    }
}
