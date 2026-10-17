package com.qytong.rfprog;

/**
 * 电台 EEPROM 镜像（0x6400 = 25600 字节）
 *
 * 每个 32 字节块 = 一个频道存储单元，内含 A/B 两个半区（对应 PC 版 "A Channal"/"B Channal"）：
 *   半区A: [0-3] RX频率BCD [4-7] TX频率BCD [8-9] QT/DQT1 [10-11] QT/DQT2
 *          [12] 未知 [13] 标志 [14] 标志 [15] 未知
 *   半区B: [16-19] RX频率BCD [20-23] TX频率BCD [24-25] QT/DQT1 [26-27] QT/DQT2
 *          [28] 未知 [29] 标志 [30] 标志 [31] 未知
 *
 * 已知标志位（半区内偏移）：
 *   字节14 bit3(0x08)=窄带(0)/宽带(1)   bit4(0x10)=开关1  bit6(0x40)=开关2  bit7(0x80)=禁止接收
 *   字节13 bit2(0x04)=开关3  bit4(0x10)=开关4  bit6-7(0xC0)=两位参数(0-3)
 */
public class EepromImage {

    public static final int SIZE = 0x6400;
    public static final int BLOCK = 32;
    public static final int INFO_ADDR = 0x1B40;   // 信息/参数块（同步命令读取的地址）
    public static final int GAP_START = 0x1F40;   // 保留区起点
    public static final int GAP_END = 0x2000;     // 保留区终点（不读写）

    public final byte[] data = new byte[SIZE];

    public EepromImage() {
    }

    public EepromImage(byte[] src) {
        System.arraycopy(src, 0, data, 0, Math.min(src.length, SIZE));
    }

    /** 由字节数组创建镜像对象（包装） */
    public static EepromImage from(byte[] src) {
        return new EepromImage(src);
    }

    /** 需要读写的地址序列（与原版软件一致，跳过保留区） */
    public static int[] addressSequence() {
        int[] seq = new int[(GAP_START / BLOCK) + ((SIZE - GAP_END) / BLOCK)];
        int n = 0;
        for (int a = 0; a < GAP_START; a += BLOCK) seq[n++] = a;
        for (int a = GAP_END; a < SIZE; a += BLOCK) seq[n++] = a;
        return seq;
    }

    public static int blockCount() {
        return addressSequence().length;
    }

    public byte[] getBlock(int addr) {
        byte[] out = new byte[BLOCK];
        System.arraycopy(data, addr, out, 0, BLOCK);
        return out;
    }

    public void setBlock(int addr, byte[] block) {
        System.arraycopy(block, 0, data, addr, BLOCK);
    }

    /** 半区（A 或 B）参数 */
    public static class Half {
        public long rxHz = -1;
        public long txHz = -1;
        public Tone qt1 = Tone.off();
        public Tone qt2 = Tone.off();
        public int flagsLo = 0;   // 字节13
        public int flagsHi = 0;   // 字节14
        public int unknown1 = 0;  // 字节12
        public int unknown2 = 0;  // 字节15

        public boolean rxValid() {
            return rxHz >= 0 && rxHz / 1e6 >= 25.0 && rxHz / 1e6 <= 600.0;
        }

        public String rxText() {
            return rxValid() ? HexUtil.freqHzToMhzText(rxHz) : "——";
        }

        public String txText() {
            if (txHz >= 0 && txHz / 1e6 >= 25.0 && txHz / 1e6 <= 600.0) {
                return HexUtil.freqHzToMhzText(txHz);
            }
            return "——";
        }

        public boolean narrow() { return (flagsHi & 0x08) != 0; }
        public boolean flag4() { return (flagsHi & 0x10) != 0; }   // 字节14 bit4
        public boolean flag6() { return (flagsHi & 0x40) != 0; }   // 字节14 bit6
        public boolean forbidRx() { return (flagsHi & 0x80) != 0; } // 字节14 bit7
        public boolean flag13bit2() { return (flagsLo & 0x04) != 0; } // 字节13 bit2
        public boolean flag13bit4() { return (flagsLo & 0x10) != 0; } // 字节13 bit4
        public int param67() { return (flagsLo >> 6) & 0x03; }      // 字节13 bit6-7
    }

    /** 频道（一个 32 字节块） */
    public static class Channel {
        public int addr;
        public byte[] raw = new byte[BLOCK];
        public Half a = new Half();
        public Half b = new Half();

        public static Channel of(int addr, byte[] raw) {
            Channel c = new Channel();
            c.addr = addr;
            c.raw = raw;
            c.a = parseHalf(raw, 0);
            c.b = parseHalf(raw, 16);
            return c;
        }

        private static Half parseHalf(byte[] raw, int o) {
            Half h = new Half();
            h.rxHz = HexUtil.bcdToFreqHz(raw, o);
            h.txHz = HexUtil.bcdToFreqHz(raw, o + 4);
            h.qt1 = Tone.decode(raw, o + 8);
            h.qt2 = Tone.decode(raw, o + 10);
            h.flagsLo = raw[o + 13] & 0xFF;
            h.flagsHi = raw[o + 14] & 0xFF;
            h.unknown1 = raw[o + 12] & 0xFF;
            h.unknown2 = raw[o + 15] & 0xFF;
            return h;
        }

        public String addrText() {
            return String.format("0x%04X", addr);
        }

        /** 摘要（列表显示） */
        public String summary() {
            String s = "";
            if (a.rxValid()) {
                s += "A: " + a.rxText() + "/" + a.txText();
                if (a.qt1.type != Tone.OFF) s += " " + a.qt1.label();
                if (a.narrow()) s += " 窄带";
            }
            if (b.rxValid()) {
                if (s.length() > 0) s += "  |  ";
                s += "B: " + b.rxText() + "/" + b.txText();
                if (b.qt1.type != Tone.OFF) s += " " + b.qt1.label();
            }
            return s.isEmpty() ? "（空）" : s;
        }
    }

    /** 全部频道块 */
    public java.util.List<Channel> channels() {
        java.util.List<Channel> list = new java.util.ArrayList<Channel>();
        for (int a : addressSequence()) {
            list.add(Channel.of(a, getBlock(a)));
        }
        return list;
    }
}
